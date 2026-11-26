"""Private bounded attachments; only the sender and visible conversation recipient may read."""
import json
import base64
import hashlib
import logging
import os
from pathlib import Path
import re
import shutil
import time
import uuid
from fastapi import Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, Response
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect
from database import PostgresConnection

ROOT = Path(os.environ.get('SALOM_MEDIA', '/var/lib/salom-api/attachments'))
MAX_FILE = 25 * 1024 * 1024
MAX_ACCOUNT = 200 * 1024 * 1024
MAX_AVATAR = 512 * 1024
logger = logging.getLogger(__name__)


def lock_sender(db, owner):
    if isinstance(db, PostgresConnection):
        db.execute('SELECT pg_advisory_xact_lock(83215, hashtext(?))', (owner,)).fetchone()

def avatar_schema(db):
    db.execute("""CREATE TABLE IF NOT EXISTS profile_avatars(
        owner_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
        revision TEXT NOT NULL, jpeg_base64 TEXT NOT NULL)""")

def schema(db):
    db.executescript('''CREATE TABLE IF NOT EXISTS attachments(
      id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      peer_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, created REAL NOT NULL,
      ready INTEGER NOT NULL DEFAULT 0);
      CREATE INDEX IF NOT EXISTS attachments_owner ON attachments(owner_id);
      CREATE TABLE IF NOT EXISTS conversation_clears(
      owner_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      peer_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
      through_id INTEGER NOT NULL, PRIMARY KEY(owner_id,peer_id));''')
    columns = {row[1] for row in db.execute('PRAGMA table_info(attachments)')}
    for name, definition in [('kind', "TEXT NOT NULL DEFAULT 'file'"), ('duration_ms', 'INTEGER NOT NULL DEFAULT 0'), ('waveform', "TEXT NOT NULL DEFAULT '[]'"), ('sha256', 'TEXT')]:
        if name not in columns: db.execute('ALTER TABLE attachments ADD COLUMN ' + name + ' ' + definition)
    if 'attachment_id' not in {row[1] for row in db.execute('PRAGMA table_info(messages)')}:
        db.execute('ALTER TABLE messages ADD COLUMN attachment_id TEXT REFERENCES attachments(id)')

ALBUM_SCHEMA = '''CREATE TABLE IF NOT EXISTS message_album_photos (
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 11),
    attachment_id TEXT NOT NULL UNIQUE REFERENCES attachments(id),
    PRIMARY KEY (message_id, position))'''

def album_extras(db, message_id):
    return [row['attachment_id'] for row in db.execute(
        'SELECT attachment_id FROM message_album_photos WHERE message_id=? ORDER BY position',
        (message_id,)).fetchall()]

def attachment_message(db, attachment_id):
    return db.execute('''SELECT id FROM messages WHERE attachment_id=?
        UNION ALL SELECT message_id AS id FROM message_album_photos WHERE attachment_id=? LIMIT 1''',
        (attachment_id, attachment_id)).fetchone()

def metadata(db, attachment_id):
    if not attachment_id: return None
    row = db.execute('SELECT id,name,mime,size,kind,duration_ms,waveform,sha256 FROM attachments WHERE id=? AND ready=1', (attachment_id,)).fetchone()
    if not row: return None
    result = dict(row)
    result['waveform'] = json.loads(result['waveform'])
    return result

def cutoff(db, owner, peer):
    row = db.execute('SELECT through_id FROM conversation_clears WHERE owner_id=? AND peer_id=?', (owner, peer)).fetchone()
    return row[0] if row else 0

def sniff_mime(prefix: bytes) -> str:
    if prefix.startswith(b"\x89PNG\r\n\x1a\n"): return "image/png"
    if prefix.startswith(b"\xff\xd8\xff"): return "image/jpeg"
    if prefix.startswith((b"GIF87a", b"GIF89a")): return "image/gif"
    if len(prefix) >= 12 and prefix[:4] == b"RIFF" and prefix[8:12] == b"WEBP": return "image/webp"
    if prefix.startswith(b"BM"): return "image/bmp"
    if prefix.startswith(b"%PDF-"): return "application/pdf"
    if prefix.startswith(b"PK\x03\x04"): return "application/zip"
    if len(prefix) >= 12 and prefix[4:8] == b"ftyp":
        brand = prefix[8:12].lower()
        return "audio/mp4" if brand in (b"m4a ", b"m4b ", b"m4p ", b"f4a ") else "video/mp4"
    if prefix.startswith(b"\x1aE\xdf\xa3"): return "video/webm"
    if prefix.startswith((b"ID3", b"OggS", b"fLaC", b"RIFF")):
        if prefix.startswith(b"OggS"): return "audio/ogg"
        if prefix.startswith(b"fLaC"): return "audio/flac"
        if prefix.startswith(b"RIFF") and prefix[8:12] == b"WAVE": return "audio/wav"
        if prefix.startswith(b"ID3"): return "audio/mpeg"
    if len(prefix) >= 2 and prefix[0] == 0xFF and (prefix[1] & 0xF6) == 0xF0: return "audio/aac"
    if prefix and b"\x00" not in prefix:
        try:
            prefix.decode("utf-8-sig")
            return "text/plain"
        except UnicodeDecodeError:
            pass
    return "application/octet-stream"

def verified_mime(declared: str, prefix: bytes, filename: str) -> str:
    actual = sniff_mime(prefix)
    declared = {"image/jpg": "image/jpeg", "audio/x-m4a": "audio/mp4"}.get(declared, declared)
    text_types = {"application/json", "application/xml", "application/javascript", "application/x-yaml", "application/yaml", "application/csv"}
    office_ext = Path(filename).suffix.lower()
    office_zip = {
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    }
    if actual == "application/zip" and office_zip.get(office_ext) == declared:
        return declared
    if actual == "text/plain" and (declared.startswith("text/") or declared in text_types):
        return declared
    if declared in ("application/octet-stream", "binary/octet-stream"):
        return actual
    if actual == "application/octet-stream":
        raise HTTPException(400, "Содержимое файла не соответствует его типу")
    if declared != actual:
        raise HTTPException(400, "Содержимое файла не соответствует его типу")
    return actual


def mp4_audio_only(path, length):
    """Android recorders may use isom/mp42 even for an AAC-only recording."""
    with path.open('rb') as source:
        remaining_boxes = 512

        def children(start, end):
            nonlocal remaining_boxes
            while start + 8 <= end:
                remaining_boxes -= 1
                if remaining_boxes < 0:
                    raise ValueError('Too many MP4 boxes')
                source.seek(start)
                raw = source.read(8)
                size = int.from_bytes(raw[:4], 'big')
                header = 8
                if size == 1:
                    size = int.from_bytes(source.read(8), 'big')
                    header = 16
                if size == 0:
                    size = end - start
                if size < header or start + size > end:
                    raise ValueError('Invalid MP4 box size')
                yield raw[4:8], start + header, start + size
                start += size

        handlers = set()
        try:
            for kind, start, end in children(0, length):
                if kind != b'moov':
                    continue
                for kind, track, track_end in children(start, end):
                    if kind != b'trak':
                        continue
                    for kind, media_start, media_end in children(track, track_end):
                        if kind != b'mdia':
                            continue
                        for kind, handler, handler_end in children(media_start, media_end):
                            if kind == b'hdlr' and handler + 12 <= handler_end:
                                source.seek(handler + 8)
                                handlers.add(source.read(4))
        except ValueError:
            return False
        return b'soun' in handlers and b'vide' not in handlers


def install_routes(app, connect, current_profile, throttle):
    @app.put('/profiles/me/avatar')
    async def put_avatar(request: Request, authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        throttle('avatar:' + owner, limit=12)
        data = bytearray()
        async for chunk in request.stream():
            if len(data) + len(chunk) > MAX_AVATAR:
                raise HTTPException(413, 'Фото профиля слишком большое')
            data.extend(chunk)
        if len(data) < 12 or not data.startswith(b'\xff\xd8\xff') or not data.endswith(b'\xff\xd9'):
            raise HTTPException(400, 'Выбери фотографию JPEG')
        revision = hashlib.sha256(data).hexdigest()
        url = f'https://mukimov.site/salom/api/avatars/{owner}/{revision}.jpg'
        with connect() as db:
            db.execute("""INSERT INTO profile_avatars(owner_id,revision,jpeg_base64) VALUES(?,?,?)
                ON CONFLICT(owner_id) DO UPDATE SET revision=excluded.revision,jpeg_base64=excluded.jpeg_base64""",
                (owner, revision, base64.b64encode(data).decode('ascii')))
            db.execute('UPDATE profiles SET avatar_url=?,updated=? WHERE id=?', (url, time.time(), owner))
        return {'avatar_url': url}

    @app.get('/avatars/{owner}/{revision}.jpg')
    def get_avatar(owner: uuid.UUID, revision: str):
        if not re.fullmatch('[0-9a-f]{64}', revision):
            raise HTTPException(404, 'Аватар недоступен')
        with connect() as db:
            row = db.execute("""SELECT a.jpeg_base64 FROM profile_avatars a JOIN profiles p ON p.id=a.owner_id
                WHERE a.owner_id=? AND a.revision=? AND p.is_active=1""", (str(owner), revision)).fetchone()
        if not row:
            raise HTTPException(404, 'Аватар недоступен')
        return Response(base64.b64decode(row['jpeg_base64']), media_type='image/jpeg',
                        headers={'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'public, max-age=60'})

    @app.post('/attachments')
    async def upload(request: Request, peer_id: str = Query(min_length=36, max_length=36), name: str = Query(min_length=1, max_length=180), kind: str = Query(default='file', pattern='^(file|voice)$'), duration_ms: int = Query(default=0, ge=0, le=300000), waveform: str = Query(default='', max_length=160), upload_id: uuid.UUID | None = Query(default=None), authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        throttle('upload-attachment-request:' + owner, limit=60)
        try: uuid.UUID(peer_id)
        except ValueError: raise HTTPException(400, 'Неверный получатель')
        if owner == peer_id: raise HTTPException(400, 'Используй Избранное для личных файлов')
        filename = name.replace('\\', '/').split('/')[-1]
        filename = ''.join(c for c in filename if c.isprintable()).strip()[:180] or 'Файл'
        mime = request.headers.get('content-type', 'application/octet-stream').split(';')[0].lower()
        if not re.fullmatch(r'[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+', mime): mime = 'application/octet-stream'
        try: peaks = [int(value) for value in waveform.split(',')] if waveform else []
        except ValueError: raise HTTPException(400, 'Неверная форма голосового сообщения')
        if len(peaks) > 32 or any(value < 0 or value > 100 for value in peaks): raise HTTPException(400, 'Неверная форма голосового сообщения')
        if kind == 'voice' and (mime not in ('audio/mp4', 'audio/aac', 'audio/x-m4a') or duration_ms < 1000 or not peaks):
            raise HTTPException(400, 'Голосовое сообщение должно длиться от 1 секунды до 5 минут')
        if kind == 'file': duration_ms, peaks = 0, []
        try: reserved = int(request.headers.get('content-length', str(MAX_FILE)))
        except ValueError: raise HTTPException(400, 'Неверный размер файла')
        if reserved <= 0 or reserved > MAX_FILE: raise HTTPException(413, 'Максимальный размер файла — 25 МБ')
        ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
        if shutil.disk_usage(ROOT).free < MAX_FILE + 512 * 1024 * 1024: raise HTTPException(503, 'Хранилище временно недоступно')
        expected_hash = request.headers.get('x-content-sha256', '').lower()
        if (upload_id or expected_hash) and not re.fullmatch('[0-9a-f]{64}', expected_hash):
            raise HTTPException(400, 'Неверная контрольная сумма файла')
        attachment_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f'salom-attachment:{owner}:{upload_id}') if upload_id else uuid.uuid4())
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            lock_sender(db, owner)
            if not db.execute('SELECT 1 FROM profiles WHERE id=? AND is_active=1', (peer_id,)).fetchone(): raise HTTPException(404, 'Получатель недоступен')
            stale = db.execute('SELECT id FROM attachments WHERE owner_id=? AND created<? AND NOT EXISTS(SELECT 1 FROM messages WHERE attachment_id=attachments.id) AND NOT EXISTS(SELECT 1 FROM message_album_photos WHERE attachment_id=attachments.id) LIMIT 32', (owner, time.time()-86400)).fetchall()
            for row in stale:
                db.execute('DELETE FROM attachments WHERE id=?', (row['id'],))
                (ROOT / row['id']).unlink(missing_ok=True)
            existing = db.execute('SELECT * FROM attachments WHERE id=?', (attachment_id,)).fetchone()
            if existing:
                if not existing['ready']:
                    raise HTTPException(409, 'Файл уже загружается. Повтори позже')
                if (existing['peer_id'], existing['name'], existing['size'], existing['kind'], existing['duration_ms'],
                    existing['waveform'], existing['sha256']) != (peer_id, filename, reserved, kind, duration_ms, json.dumps(peaks), expected_hash):
                    raise HTTPException(409, 'Этот идентификатор уже использован для другого файла')
                return metadata(db, attachment_id)
            throttle('upload-attachment:' + owner, limit=24)
            used = db.execute('SELECT COALESCE(SUM(size),0) FROM attachments WHERE owner_id=?', (owner,)).fetchone()[0]
            if used + reserved > MAX_ACCOUNT: raise HTTPException(413, 'Лимит вложений аккаунта — 200 МБ')
            db.execute('INSERT INTO attachments(id,owner_id,peer_id,name,mime,size,created,ready,kind,duration_ms,waveform) VALUES(?,?,?,?,?,?,?,0,?,?,?)', (attachment_id, owner, peer_id, filename, mime, reserved, time.time(), kind, duration_ms, json.dumps(peaks)))
        path = ROOT / attachment_id
        complete = False
        first_chunk = bytearray()
        digest = hashlib.sha256()
        try:
            total = 0
            with path.open('xb') as output:
                path.chmod(0o600)
                async for chunk in request.stream():
                    total += len(chunk)
                    if total > reserved or total > MAX_FILE: raise HTTPException(413, 'Максимальный размер файла — 25 МБ')
                    if len(first_chunk) < 4096:
                        first_chunk.extend(chunk[:4096 - len(first_chunk)])
                    digest.update(chunk)
                    await run_in_threadpool(output.write, chunk)
            if total == 0: raise HTTPException(400, 'Файл пустой')
            if request.headers.get('content-length') and total != reserved:
                raise HTTPException(400, 'Файл загружен не полностью')
            checksum = digest.hexdigest()
            if expected_hash and checksum != expected_hash:
                raise HTTPException(422, 'Контрольная сумма файла не совпала')
            prefix = bytes(first_chunk)
            if (kind == 'voice' and mime in ('audio/mp4', 'audio/x-m4a')
                    and sniff_mime(prefix) == 'video/mp4' and await run_in_threadpool(mp4_audio_only, path, total)):
                mime = 'audio/mp4'
            else:
                mime = verified_mime(mime, prefix, filename)
            with connect() as db:
                db.execute('UPDATE attachments SET size=?,mime=?,sha256=?,ready=1 WHERE id=?', (total, mime, checksum, attachment_id))
                result = metadata(db, attachment_id)
            complete = True
            return result
        except ClientDisconnect:
            raise HTTPException(400, 'Загрузка прервана') from None
        except OSError as exc:
            logger.warning('Attachment write failed, errno=%s', exc.errno)
            raise HTTPException(503, 'Хранилище временно недоступно') from None
        finally:
            if not complete:
                try:
                    path.unlink(missing_ok=True)
                except OSError as exc:
                    logger.warning('Interrupted upload cleanup failed, errno=%s', exc.errno)
                else:
                    with connect() as db: db.execute('DELETE FROM attachments WHERE id=?', (attachment_id,))

    @app.get('/attachments/{attachment_id}')
    def download(attachment_id: uuid.UUID, authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        with connect() as db:
            attachment = db.execute('SELECT * FROM attachments WHERE id=? AND ready=1', (str(attachment_id),)).fetchone()
            if not attachment: raise HTTPException(404, 'Файл недоступен')
            peer = attachment['peer_id'] if attachment['owner_id'] == owner else attachment['owner_id']
            message = attachment_message(db, str(attachment_id))
            hidden = message and db.execute('SELECT 1 FROM message_hides WHERE owner_id=? AND message_id=?', (owner, message['id'])).fetchone()
            allowed = owner in (attachment['owner_id'], attachment['peer_id']) and message and not hidden and message['id'] > cutoff(db, owner, peer)
            pending_owner = owner == attachment['owner_id'] and not message
            if not (allowed or pending_owner): raise HTTPException(404, 'Файл недоступен')
        path = ROOT / str(attachment_id)
        if not path.is_file(): raise HTTPException(404, 'Файл недоступен')
        headers = {'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff'}
        if attachment['sha256']:
            headers['X-Content-SHA256'] = attachment['sha256']
        return FileResponse(path, media_type=attachment['mime'], filename=attachment['name'], headers=headers)

    @app.delete('/messages')
    def clear(peer_id: uuid.UUID, authorization: str | None = Header(default=None)):
        owner = current_profile(authorization)['id']
        peer = str(peer_id)
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM profiles WHERE id=?', (peer,)).fetchone(): raise HTTPException(404, 'Собеседник недоступен')
            through = db.execute('SELECT COALESCE(MAX(id),0) FROM messages WHERE (sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)', (owner, peer, peer, owner)).fetchone()[0]
            db.execute('INSERT INTO conversation_clears VALUES(?,?,?) ON CONFLICT(owner_id,peer_id) DO UPDATE SET through_id=MAX(through_id,excluded.through_id)', (owner, peer, through))
        return {'cleared_through': through}
