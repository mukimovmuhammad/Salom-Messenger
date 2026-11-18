"""Salom profiles, messaging and verified accounts. Address books are never collected."""
import hashlib
import hmac
import base64
import asyncio
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException, Query, Request, WebSocket
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict, Field
from redis import Redis as SyncRedis
from redis.asyncio import Redis as AsyncRedis
import media
from database import INTEGRITY_ERRORS, PostgresConnection, create_pool
from realtime import RealtimeHub
from push import PushDispatcher
from auth import schema as auth_schema, install_routes as install_auth_routes
from monitoring import RuntimeMetrics, RuntimeReporter

DB = Path(os.environ.get('SALOM_DB', '/var/lib/salom-api/directory.sqlite3'))
PG_DSN = os.environ.get('SALOM_DATABASE_URL', '').strip()
REDIS_URL = os.environ.get('REDIS_URL', '').strip()
redis_sync = None
redis_async = None
pg_pool = None
pg_pool_lock = threading.Lock()
USERNAME = re.compile(r'^[A-Za-z][A-Za-z0-9_]{3,23}$')
TOKEN = re.compile(r'^[A-Za-z0-9_-]{43,128}$')
rate_lock = threading.Lock()
rates = defaultdict(deque)

@contextmanager
def connect():
    if PG_DSN:
        pool = get_pg_pool()
        connection = pool.getconn()
        try:
            yield PostgresConnection(connection)
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            pool.putconn(connection)
        return
    db = sqlite3.connect(DB, timeout=10)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    try:
        # sqlite3.Connection.__exit__ commits/rolls back but does not close the
        # file descriptor. Every caller uses ``with connect()``; close it here
        # so requests cannot accumulate database and WAL descriptors.
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()

def get_pg_pool():
    global pg_pool
    if pg_pool is None:
        with pg_pool_lock:
            if pg_pool is None:
                pg_pool = create_pool(PG_DSN)
    return pg_pool

def close_pg_pool():
    global pg_pool
    with pg_pool_lock:
        if pg_pool is not None:
            pg_pool.close(timeout=10)
            pg_pool = None

def initialize():
    if os.environ.get('SALOM_REQUIRE_POSTGRES') == '1' and not PG_DSN:
        raise RuntimeError('SALOM_DATABASE_URL is required on this server')
    if PG_DSN:
        required = {
            'profiles': 'id,token_hash,name,username,username_key,is_active,created,updated,online_at,typing_to,typing_at,avatar_url',
            'accounts': 'profile_id,firebase_uid,phone,email,password_hash,created,password_changed,google_linked',
            'sessions': 'token_hash,profile_id,created,expires,verified_at',
            'email_challenges': 'id,email,code_hash,expires,created,attempts,ready,verified,ticket_hash,used',
            'attachments': 'id,owner_id,peer_id,name,mime,size,created,ready,kind,duration_ms,waveform',
            'messages': 'id,sender_id,recipient_id,client_id,text,created,delivered_at,read_at,reply_to_id,edited_at,location_id,attachment_id',
            'message_events': 'id,message_id,sender_id,recipient_id,owner_id,kind',
            'message_hides': 'owner_id,message_id', 'live_locations': 'id,owner_id,peer_id,created,expires,latitude,longitude,updated,active',
            'conversation_clears': 'owner_id,peer_id,through_id', 'push_tokens': 'profile_id,token,updated',
            'push_jobs': 'message_id,state,attempts,next_at,last_error', 'registrations': 'ip,at', 'auth_limits': 'key,at',
        }
        with connect() as db:
            # Additive call schema; safe to apply on existing PostgreSQL installs.
            db.executescript('''CREATE TABLE IF NOT EXISTS call_sessions (
              id TEXT PRIMARY KEY, caller_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
              callee_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
              state TEXT NOT NULL, created DOUBLE PRECISION NOT NULL, updated DOUBLE PRECISION NOT NULL,
              expires DOUBLE PRECISION NOT NULL, ended_by TEXT, seen_at DOUBLE PRECISION);
              CREATE INDEX IF NOT EXISTS call_sessions_callee ON call_sessions(callee_id,state,created);
              CREATE TABLE IF NOT EXISTS call_push_jobs (
              call_id TEXT PRIMARY KEY REFERENCES call_sessions(id) ON DELETE CASCADE,
              state TEXT NOT NULL DEFAULT 'pending', attempts BIGINT NOT NULL DEFAULT 0,
              next_at DOUBLE PRECISION NOT NULL DEFAULT 0, last_error TEXT);
              CREATE INDEX IF NOT EXISTS call_push_jobs_pending ON call_push_jobs(state,next_at);''')
            for table, columns in required.items():
                db.execute(f'SELECT {columns} FROM {table} LIMIT 0')
        return
    DB.parent.mkdir(parents=True, exist_ok=True)
    with connect() as db:
        db.executescript('''PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS profiles (
          id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
          username TEXT, username_key TEXT UNIQUE, is_active INTEGER NOT NULL DEFAULT 1,
          created REAL NOT NULL, updated REAL NOT NULL, online_at REAL NOT NULL DEFAULT 0,
          typing_to TEXT, typing_at REAL NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS registrations (ip TEXT NOT NULL, at REAL NOT NULL);
        CREATE INDEX IF NOT EXISTS registrations_ip ON registrations(ip, at);
        CREATE TABLE IF NOT EXISTS messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          sender_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
          recipient_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
          client_id TEXT NOT NULL, text TEXT NOT NULL, created REAL NOT NULL,
          delivered_at REAL, read_at REAL, reply_to_id INTEGER,
          UNIQUE(sender_id, client_id));
        CREATE INDEX IF NOT EXISTS messages_pair ON messages(sender_id, recipient_id, id);
        CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient_id, id);
        CREATE TABLE IF NOT EXISTS message_events(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER NOT NULL, sender_id TEXT NOT NULL, recipient_id TEXT NOT NULL, owner_id TEXT, kind TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS message_events_pair ON message_events(sender_id,recipient_id,id);
        CREATE TABLE IF NOT EXISTS message_hides(owner_id TEXT NOT NULL, message_id INTEGER NOT NULL, PRIMARY KEY(owner_id,message_id));
        CREATE TABLE IF NOT EXISTS live_locations(id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, peer_id TEXT NOT NULL, created REAL NOT NULL, expires REAL NOT NULL, latitude REAL, longitude REAL, updated REAL NOT NULL, active INTEGER NOT NULL DEFAULT 1);
        CREATE TABLE IF NOT EXISTS push_tokens (
          profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
          token TEXT NOT NULL UNIQUE, updated REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS push_jobs (
          message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
          state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
          next_at REAL NOT NULL DEFAULT 0, last_error TEXT);
        CREATE INDEX IF NOT EXISTS push_jobs_pending ON push_jobs(state,next_at);
        CREATE TABLE IF NOT EXISTS call_sessions (
          id TEXT PRIMARY KEY, caller_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
          callee_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
          state TEXT NOT NULL, created REAL NOT NULL, updated REAL NOT NULL,
          expires REAL NOT NULL, ended_by TEXT, seen_at REAL);
        CREATE INDEX IF NOT EXISTS call_sessions_callee ON call_sessions(callee_id,state,created);
        CREATE TABLE IF NOT EXISTS call_push_jobs (
          call_id TEXT PRIMARY KEY REFERENCES call_sessions(id) ON DELETE CASCADE,
          state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
          next_at REAL NOT NULL DEFAULT 0, last_error TEXT);
        CREATE INDEX IF NOT EXISTS call_push_jobs_pending ON call_push_jobs(state,next_at);
        ''')
        profile_columns = {row['name'] for row in db.execute('PRAGMA table_info(profiles)')}
        if 'online_at' not in profile_columns: db.execute('ALTER TABLE profiles ADD COLUMN online_at REAL NOT NULL DEFAULT 0')
        if 'typing_to' not in profile_columns: db.execute('ALTER TABLE profiles ADD COLUMN typing_to TEXT')
        if 'typing_at' not in profile_columns: db.execute('ALTER TABLE profiles ADD COLUMN typing_at REAL NOT NULL DEFAULT 0')
        if 'avatar_url' not in profile_columns: db.execute('ALTER TABLE profiles ADD COLUMN avatar_url TEXT')
        message_columns = {row['name'] for row in db.execute('PRAGMA table_info(messages)')}
        if 'delivered_at' not in message_columns: db.execute('ALTER TABLE messages ADD COLUMN delivered_at REAL')
        if 'read_at' not in message_columns: db.execute('ALTER TABLE messages ADD COLUMN read_at REAL')
        if 'reply_to_id' not in message_columns: db.execute('ALTER TABLE messages ADD COLUMN reply_to_id INTEGER')
        if 'edited_at' not in message_columns: db.execute('ALTER TABLE messages ADD COLUMN edited_at REAL')
        if 'location_id' not in message_columns: db.execute('ALTER TABLE messages ADD COLUMN location_id TEXT')
        auth_schema(db)
        # Preserve the legacy email-verification table during SQLite/PostgreSQL migration.
        db.executescript('''CREATE TABLE IF NOT EXISTS email_challenges (
          id TEXT PRIMARY KEY, email TEXT NOT NULL, code_hash TEXT NOT NULL,
          expires REAL NOT NULL, created REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
          ready INTEGER NOT NULL DEFAULT 0, verified REAL, ticket_hash TEXT, used REAL);
          CREATE INDEX IF NOT EXISTS email_challenges_email ON email_challenges(email,created);
        ''')
        media.schema(db)

@asynccontextmanager
async def lifespan(app):
    initialize()
    app.state.realtime = RealtimeHub()
    app.state.event_loop = asyncio.get_running_loop()
    if os.environ.get('SALOM_REQUIRE_REDIS') == '1' and not REDIS_URL:
        raise RuntimeError('REDIS_URL is required on this server')
    relay_task = None
    if REDIS_URL:
        global redis_sync, redis_async
        redis_sync = SyncRedis.from_url(REDIS_URL, decode_responses=True, socket_connect_timeout=2, socket_timeout=2)
        redis_async = AsyncRedis.from_url(REDIS_URL, decode_responses=True, socket_connect_timeout=2, socket_timeout=2)
        await redis_async.ping()
        app.state.redis_sync = redis_sync
        app.state.redis_async = redis_async
        relay_task = asyncio.create_task(redis_event_relay(app), name='salom-redis-event-relay')
    app.state.runtime_metrics = RuntimeMetrics()
    reporter = RuntimeReporter(app.state.runtime_metrics, lambda: app.state.realtime.connection_count)
    try:
        reporter.start()
        app.state.push = PushDispatcher(connect)
        app.state.push.start()
        yield
    finally:
        if relay_task:
            relay_task.cancel()
            try: await relay_task
            except asyncio.CancelledError: pass
        if redis_async:
            await redis_async.aclose()
            redis_async = None
        if redis_sync:
            redis_sync.close()
            redis_sync = None
        push = getattr(app.state, 'push', None)
        if push is not None:
            push.stop()
        reporter.stop()
        close_pg_pool()

async def redis_event_relay(application):
    """Fan out durable API events to each worker's local WebSocket hub."""
    channel = 'salom:realtime:v1'
    while True:
        try:
            async with application.state.redis_async.pubsub() as pubsub:
                await pubsub.subscribe(channel)
                async for item in pubsub.listen():
                    if item.get('type') != 'message': continue
                    try:
                        envelope = json.loads(item['data'])
                        profile_id, event = envelope['profile_id'], envelope['event']
                        if isinstance(profile_id, str) and isinstance(event, dict):
                            await application.state.realtime.publish(profile_id, event)
                    except (KeyError, TypeError, ValueError):
                        continue
        except asyncio.CancelledError:
            raise
        except Exception:
            # Reconnect without logging any message contents or identifiers.
            await asyncio.sleep(1)

app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

def publish_realtime(profile_id: str, event: dict) -> None:
    hub = getattr(app.state, 'realtime', None)
    loop = getattr(app.state, 'event_loop', None)
    if hub is None or loop is None or loop.is_closed():
        return
    def schedule_publish():
        async def send():
            client = getattr(app.state, 'redis_async', None)
            if client is not None:
                try:
                    await client.publish('salom:realtime:v1', json.dumps({'profile_id': profile_id, 'event': event}, separators=(',', ':')))
                    return
                except Exception:
                    pass
            await hub.publish(profile_id, event)
        asyncio.create_task(send())
    loop.call_soon_threadsafe(schedule_publish)

@app.websocket('/ws')
async def realtime_socket(socket: WebSocket):
    try:
        profile = current_profile(socket.headers.get('authorization'))
    except HTTPException:
        await socket.close(code=4401, reason='Authentication required')
        return
    await app.state.realtime.serve(socket, profile['id'])

@app.exception_handler(RequestValidationError)
async def validation_error(request, exception):
    # Validation responses must not echo submitted passwords, verification codes or tokens.
    return JSONResponse({'detail': 'Проверь введённые данные'}, status_code=422)

@app.middleware('http')
async def bounds(request: Request, call_next):
    started = time.perf_counter()
    status = 500
    try:
        if int(request.headers.get('content-length', '0') or 0) > (media.MAX_FILE if request.url.path == '/attachments' else 65536):
            response = JSONResponse({'detail': 'Слишком большой запрос'}, status_code=413)
        else:
            response = await call_next(request)
            response.headers['Cache-Control'] = 'no-store'
            response.headers['X-Content-Type-Options'] = 'nosniff'
        status = response.status_code
        return response
    finally:
        metrics = getattr(app.state, 'runtime_metrics', None)
        if metrics is not None:
            metrics.record_request(request.method, status, (time.perf_counter() - started) * 1000)

def digest(authorization):
    if not authorization or not authorization.startswith('Bearer '):
        raise HTTPException(401, 'Требуется авторизация устройства')
    token = authorization[7:]
    if not TOKEN.fullmatch(token):
        raise HTTPException(401, 'Неверный ключ устройства')
    return hashlib.sha256(token.encode()).hexdigest()

def public(row):
    return {'id': row['id'], 'name': row['name'], 'username': row['username'] or '', 'is_active': bool(row['is_active']), 'avatar_url': row['avatar_url'] or ''}

def with_presence(row):
    result = public(row)
    seen = float(row['online_at'] or 0)
    is_online = seen >= time.time() - 45
    client = redis_sync
    if client is not None:
        try: is_online = bool(client.exists('salom:presence:' + str(row['id'])))
        except Exception: pass
    result.update({'is_online': is_online, 'last_seen': seen or None})
    return result

def throttle(key, limit=180):
    client = redis_sync
    if client is not None:
        try:
            hashed = hashlib.sha256(str(key).encode()).hexdigest()
            bucket = f'salom:rate:{hashed}:{int(time.time() // 60)}'
            pipe = client.pipeline(transaction=True)
            pipe.incr(bucket)
            pipe.expire(bucket, 120)
            count, _ = pipe.execute()
            if count > limit: raise HTTPException(429, 'Слишком много запросов. Попробуй через минуту')
            return
        except HTTPException: raise
        except Exception:
            if os.environ.get('SALOM_REQUIRE_REDIS') == '1':
                raise HTTPException(503, 'Ограничитель запросов временно недоступен')
    now = time.monotonic()
    with rate_lock:
        window = rates[key]
        while window and window[0] < now - 60: window.popleft()
        if len(window) >= limit: raise HTTPException(429, 'Слишком много запросов. Попробуй через минуту')
        window.append(now)
        if len(rates) > 10000:
            for stale in [k for k, v in rates.items() if not v or v[-1] < now - 60]: rates.pop(stale, None)

class ProfileInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    device_id: str = Field(min_length=36, max_length=36)
    name: str = Field(min_length=1, max_length=80)
    username: str = Field(default='', max_length=24)

@app.get('/health')
def health():
    with connect() as db: db.execute('SELECT 1').fetchone()
    redis_ready = False
    if redis_sync is not None:
        try: redis_ready = bool(redis_sync.ping())
        except Exception: pass
    if os.environ.get('SALOM_REQUIRE_REDIS') == '1' and not redis_ready:
        raise HTTPException(503, 'Кэш временно недоступен')
    return {'status': 'ok', 'service': 'salom-directory', 'push_configured': app.state.push.configured, 'redis_ready': redis_ready}

@app.put('/profile')
def profile(body: ProfileInput, request: Request, authorization: str | None = Header(default=None)):
    hashed = digest(authorization)
    throttle(hashed)
    try: UUID(body.device_id)
    except ValueError: raise HTTPException(400, 'Неверный идентификатор устройства')
    name = body.name.strip()
    username = body.username.strip().removeprefix('@')
    if not name: raise HTTPException(400, 'Укажи имя')
    if username and not USERNAME.fullmatch(username): raise HTTPException(400, 'Username: 4–24 латинские буквы, цифры и _, начинается с буквы')
    now = time.time()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        existing = db.execute('SELECT * FROM profiles WHERE id=?', (body.device_id,)).fetchone()
        if not existing:
            raise HTTPException(426, 'Обнови Салом и войди через Google для регистрации')
        if existing and not hmac.compare_digest(existing['token_hash'], hashed):
            session = db.execute('SELECT profile_id FROM sessions WHERE token_hash=? AND expires>?', (hashed, now)).fetchone()
            if not session or session['profile_id'] != body.device_id:
                raise HTTPException(403, 'Этот профиль принадлежит другому устройству')
        if existing and db.execute('SELECT 1 FROM accounts WHERE profile_id=?', (body.device_id,)).fetchone() and not username:
            raise HTTPException(400, 'У подтверждённого аккаунта должен быть username')
        if existing and username.lower() != (existing['username_key'] or '') and not db.execute('SELECT 1 FROM accounts WHERE profile_id=?', (body.device_id,)).fetchone():
            raise HTTPException(403, 'Подключи Google перед выбором нового username')
        try:
            db.execute('UPDATE profiles SET name=?,username=?,username_key=?,updated=? WHERE id=?', (name, username or None, username.lower() or None, now, body.device_id))
        except INTEGRITY_ERRORS:
            raise HTTPException(409, 'Этот username уже занят. Выбери другой')
        return public(db.execute('SELECT * FROM profiles WHERE id=?', (body.device_id,)).fetchone())

@app.get('/search')
def search(q: str = Query(min_length=1, max_length=64), authorization: str | None = Header(default=None)):
    current_profile(authorization)
    with connect() as db:
        prefix = q.strip().removeprefix('@').lower()
        if not re.fullmatch(r'[a-z0-9_]{1,24}', prefix): return {'profiles': []}
        pattern = prefix.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'
        rows = db.execute("SELECT * FROM profiles WHERE is_active=1 AND username_key LIKE ? ESCAPE '\\' ORDER BY CASE WHEN username_key=? THEN 0 ELSE 1 END, username_key LIMIT 20", (pattern, prefix)).fetchall()
        return {'profiles': [public(row) for row in rows]}

def current_profile(authorization):
    hashed = digest(authorization)
    with connect() as db:
        row = db.execute('SELECT * FROM profiles WHERE token_hash=? AND is_active=1', (hashed,)).fetchone()
        if not row:
            row = db.execute('SELECT p.* FROM profiles p JOIN sessions s ON s.profile_id=p.id WHERE s.token_hash=? AND s.expires>? AND p.is_active=1', (hashed, time.time())).fetchone()
        if not row: raise HTTPException(401, 'Требуется активный профиль')
        throttle(hashed)
        return dict(row)

install_auth_routes(app, connect, current_profile, public, digest)
media.install_routes(app, connect, current_profile, throttle)

def message_json(row, db):
    receipt = 'read' if row['read_at'] else 'delivered' if row['delivered_at'] else 'sent'
    reply = None
    if row['reply_to_id']:
        ref = db.execute('SELECT m.id,m.sender_id,m.text,m.attachment_id,a.kind,a.name FROM messages m LEFT JOIN attachments a ON a.id=m.attachment_id WHERE m.id=?', (row['reply_to_id'],)).fetchone()
        if ref:
            preview = ref['text'] or ('Фото' if ref['kind'] == 'image' else 'Видео' if ref['kind'] == 'video' else 'Голосовое сообщение' if ref['kind'] == 'voice' else ref['name'] or 'Вложение')
            reply = {'id': ref['id'], 'sender_id': ref['sender_id'], 'text': preview[:300]}
    return {**{key: row[key] for key in ('id', 'sender_id', 'recipient_id', 'text', 'created')}, 'edited_at': row['edited_at'], 'receipt': receipt, 'attachment': media.metadata(db, row['attachment_id']), 'reply_to': reply, 'location_id': row['location_id']}

class LiveLocationInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    peer_id: str = Field(min_length=36,max_length=36)
    duration_minutes: int = Field(ge=15,le=1440)

class LocationPoint(BaseModel):
    model_config = ConfigDict(extra='forbid')
    latitude: float = Field(ge=-90,le=90)
    longitude: float = Field(ge=-180,le=180)

class SingleLocationInput(LocationPoint):
    peer_id: str = Field(min_length=36,max_length=36)

@app.post('/locations/once')
def send_location_once(body: SingleLocationInput, authorization: str | None = Header(default=None)):
    me=current_profile(authorization)
    try: UUID(body.peer_id)
    except ValueError: raise HTTPException(400,'Неверный собеседник')
    now=time.time(); share=str(uuid.uuid4())
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        if body.peer_id==me['id'] or not db.execute('SELECT 1 FROM profiles WHERE id=? AND is_active=1',(body.peer_id,)).fetchone(): raise HTTPException(404,'Получатель недоступен')
        db.execute('INSERT INTO live_locations(id,owner_id,peer_id,created,expires,latitude,longitude,updated,active) VALUES(?,?,?,?,?,?,?,?,0)',(share,me['id'],body.peer_id,now,now+7*86400,body.latitude,body.longitude,now))
        cur=db.execute('INSERT INTO messages(sender_id,recipient_id,client_id,text,created,location_id) VALUES(?,?,?,?,?,?)',(me['id'],body.peer_id,str(uuid.uuid4()),'Отправил геопозицию',now,share))
        db.execute('INSERT INTO push_jobs(message_id) VALUES(?)',(cur.lastrowid,)); app.state.push.wake.set()
        message=message_json(db.execute('SELECT * FROM messages WHERE id=?',(cur.lastrowid,)).fetchone(),db)
    return {'message':message}

@app.post('/locations')
def start_location(body: LiveLocationInput, authorization: str | None = Header(default=None)):
    me=current_profile(authorization)
    try: UUID(body.peer_id)
    except ValueError: raise HTTPException(400,'Неверный собеседник')
    if body.peer_id==me['id']: raise HTTPException(400,'Выбери собеседника')
    now=time.time(); share=str(uuid.uuid4()); client_id=str(uuid.uuid4())
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        if not db.execute('SELECT 1 FROM profiles WHERE id=? AND is_active=1',(body.peer_id,)).fetchone(): raise HTTPException(404,'Получатель недоступен')
        db.execute('INSERT INTO live_locations(id,owner_id,peer_id,created,expires,updated) VALUES(?,?,?,?,?,?)',(share,me['id'],body.peer_id,now,now+body.duration_minutes*60,now))
        cur=db.execute('INSERT INTO messages(sender_id,recipient_id,client_id,text,created,location_id) VALUES(?,?,?,?,?,?)',(me['id'],body.peer_id,client_id,'Поделился геопозицией в реальном времени',now,share))
        db.execute('INSERT INTO push_jobs(message_id) VALUES(?)',(cur.lastrowid,)); app.state.push.wake.set()
        row=db.execute('SELECT * FROM messages WHERE id=?',(cur.lastrowid,)).fetchone()
    return {'id':share,'message':message_json(row,db),'expires':now+body.duration_minutes*60}

@app.put('/locations/{share_id}')
def update_location(share_id: uuid.UUID, body: LocationPoint, authorization: str | None = Header(default=None)):
    me=current_profile(authorization); now=time.time()
    with connect() as db:
        cur=db.execute('UPDATE live_locations SET latitude=?,longitude=?,updated=? WHERE id=? AND owner_id=? AND active=1 AND expires>?',(body.latitude,body.longitude,now,str(share_id),me['id'],now))
        if cur.rowcount!=1: raise HTTPException(404,'Передача геопозиции завершена')
    return {'updated':now}

@app.get('/locations/{share_id}')
def get_location(share_id: uuid.UUID, authorization: str | None = Header(default=None)):
    me=current_profile(authorization); now=time.time()
    with connect() as db:
        row=db.execute('SELECT * FROM live_locations WHERE id=? AND (owner_id=? OR peer_id=?)',(str(share_id),me['id'],me['id'])).fetchone()
        if not row: raise HTTPException(404,'Геопозиция недоступна')
        if row['expires']<=now and row['active']: db.execute('UPDATE live_locations SET active=0 WHERE id=?',(str(share_id),)); row=dict(row); row['active']=0
        return {'id':row['id'],'owner_id':row['owner_id'],'peer_id':row['peer_id'],'expires':row['expires'],'updated':row['updated'],'active':bool(row['active']) and row['expires']>now,'latitude':row['latitude'],'longitude':row['longitude']}

@app.delete('/locations/{share_id}')
def stop_location(share_id: uuid.UUID, authorization: str | None = Header(default=None)):
    me=current_profile(authorization)
    with connect() as db:
        cur=db.execute('UPDATE live_locations SET active=0 WHERE id=? AND owner_id=? AND active=1',(str(share_id),me['id']))
        if cur.rowcount!=1: raise HTTPException(404,'Передача уже завершена')
    return {'stopped':True}

def message_scope(db, me, message_id):
    row = db.execute('SELECT * FROM messages WHERE id=? AND (sender_id=? OR recipient_id=?)', (message_id, me, me)).fetchone()
    if not row: raise HTTPException(404, 'Сообщение уже недоступно')
    return row

@app.put('/messages/{message_id}')
def edit_message(message_id: int, body: dict, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    text = str(body.get('text', '')).strip()
    if not text or len(text) > 4000: raise HTTPException(400, 'Сообщение должно содержать от 1 до 4000 символов')
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = message_scope(db, me['id'], message_id)
        if row['sender_id'] != me['id'] or row['attachment_id']: raise HTTPException(403, 'Можно изменить только своё текстовое сообщение')
        db.execute('UPDATE messages SET text=?,edited_at=? WHERE id=?', (text, time.time(), message_id))
        db.execute('INSERT INTO message_events(message_id,sender_id,recipient_id,kind) VALUES(?,?,?,\'edit\')', (message_id,row['sender_id'],row['recipient_id']))
        result = message_json(db.execute('SELECT * FROM messages WHERE id=?',(message_id,)).fetchone(),db)
        sender, recipient = row['sender_id'], row['recipient_id']
    publish_realtime(recipient, {'type': 'sync', 'peer_id': sender})
    publish_realtime(sender, {'type': 'sync', 'peer_id': recipient})
    return result

@app.delete('/messages/{message_id}')
def delete_message(message_id: int, scope: str = Query(default='me', pattern='^(me|everyone)$'), authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = message_scope(db, me['id'], message_id)
        sender, recipient = row['sender_id'], row['recipient_id']
        if scope == 'everyone':
            if sender != me['id']: raise HTTPException(403, 'Удалить у всех может только автор сообщения')
            db.execute('INSERT INTO message_events(message_id,sender_id,recipient_id,kind) VALUES(?,?,?,\'delete\')', (message_id,sender,recipient))
            attachment_id = row['attachment_id']
            db.execute('DELETE FROM messages WHERE id=?', (message_id,))
            if attachment_id:
                db.execute('DELETE FROM attachments WHERE id=?', (attachment_id,))
                media.ROOT.joinpath(attachment_id).unlink(missing_ok=True)
        else:
            db.execute('INSERT OR IGNORE INTO message_hides(owner_id,message_id) VALUES(?,?)', (me['id'],message_id))
            db.execute('INSERT INTO message_events(message_id,sender_id,recipient_id,owner_id,kind) VALUES(?,?,?,?,\'hide\')', (message_id,sender,recipient,me['id']))
    if scope == 'everyone':
        publish_realtime(recipient, {'type': 'sync', 'peer_id': sender})
        publish_realtime(sender, {'type': 'sync', 'peer_id': recipient})
    else:
        other = recipient if sender == me['id'] else sender
        publish_realtime(me['id'], {'type': 'sync', 'peer_id': other})
    return {'deleted': True}

class MessageInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    recipient_id: str = Field(min_length=36, max_length=36)
    client_id: str = Field(min_length=36, max_length=36)
    text: str = Field(default='', max_length=4000)
    attachment_id: str | None = Field(default=None, min_length=36, max_length=36)
    reply_to_id: int | None = Field(default=None, ge=1)

@app.post('/messages')
def send_message(body: MessageInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    try:
        UUID(body.recipient_id)
        UUID(body.client_id)
    except ValueError: raise HTTPException(400, 'Неверный идентификатор сообщения или получателя')
    text = body.text.strip()
    if not text and not body.attachment_id: raise HTTPException(400, 'Сообщение не может быть пустым')
    if body.recipient_id == me['id']: raise HTTPException(400, 'Для личных заметок используй Избранное')
    # Redis shares per-account request limits across the API workers.
    throttle('send-message:' + me['id'], limit=60)
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        if not db.execute('SELECT id FROM profiles WHERE id=? AND is_active=1', (body.recipient_id,)).fetchone():
            raise HTTPException(404, 'Получатель недоступен')
        if body.reply_to_id is not None:
            cleared = media.cutoff(db, me['id'], body.recipient_id)
            reference = db.execute('''SELECT id FROM messages WHERE id>? AND id=? AND
              ((sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?))''',
              (cleared, body.reply_to_id, me['id'], body.recipient_id, body.recipient_id, me['id'])).fetchone()
            if not reference: raise HTTPException(400, 'Сообщение для ответа недоступно')
        existing = db.execute('SELECT * FROM messages WHERE sender_id=? AND client_id=?', (me['id'], body.client_id)).fetchone()
        if existing:
            if existing['recipient_id'] != body.recipient_id or existing['text'] != text or existing['attachment_id'] != body.attachment_id or existing['reply_to_id'] != body.reply_to_id:
                raise HTTPException(409, 'Этот идентификатор уже использован для другого сообщения')
            return message_json(existing, db)
        if body.attachment_id:
            attachment = db.execute('SELECT * FROM attachments WHERE id=? AND owner_id=? AND peer_id=? AND ready=1', (body.attachment_id, me['id'], body.recipient_id)).fetchone()
            if not attachment: raise HTTPException(400, 'Вложение недоступно')
            if db.execute('SELECT 1 FROM messages WHERE attachment_id=?', (body.attachment_id,)).fetchone(): raise HTTPException(409, 'Вложение уже отправлено')
        cursor = db.execute('INSERT INTO messages(sender_id,recipient_id,client_id,text,created,attachment_id,reply_to_id) VALUES(?,?,?,?,?,?,?)', (me['id'], body.recipient_id, body.client_id, text, time.time(), body.attachment_id, body.reply_to_id))
        db.execute('INSERT INTO push_jobs(message_id) VALUES(?)', (cursor.lastrowid,))
        outgoing = message_json(db.execute('SELECT * FROM messages WHERE id=?', (cursor.lastrowid,)).fetchone(), db)
    app.state.push.wake.set()
    publish_realtime(body.recipient_id, {'type': 'message', 'peer_id': me['id'], 'message': outgoing})
    return outgoing

class PushTokenInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    token: str = Field(min_length=20, max_length=4096, pattern=r'^[A-Za-z0-9_:\-]+$')
    enabled: bool = True

class CallCreateInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    peer_id: str = Field(min_length=36, max_length=36)

@app.get('/calls/config')
def call_config(authorization: str | None = Header(default=None)):
    current_profile(authorization)
    servers = [{'urls':['stun:stun.l.google.com:19302','stun:stun1.l.google.com:19302']}]
    urls = [item.strip() for item in os.environ.get('SALOM_TURN_URLS','').split(',') if item.strip()]
    secret = os.environ.get('SALOM_TURN_SECRET','')
    if urls and secret:
        expiry = int(time.time()) + 3600
        username = f'{expiry}:salom'
        credential = base64.b64encode(hmac.new(secret.encode(), username.encode(), hashlib.sha1).digest()).decode()
        servers.append({'urls':urls,'username':username,'credential':credential})
    return {'ice_servers':servers}

class CallStateInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    action: str = Field(pattern=r'^(accept|decline|cancel|end|missed)$')

class CallSignalInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    kind: str = Field(pattern=r'^(offer|answer|ice)$')
    payload: dict = Field()

@app.post('/calls')
def create_call(body: CallCreateInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    try: peer_id = str(UUID(body.peer_id))
    except ValueError: raise HTTPException(422, 'Неверный профиль')
    if peer_id == me['id']: raise HTTPException(400, 'Нельзя позвонить себе')
    now = time.time(); call_id = str(uuid.uuid4())
    with connect() as db:
        peer = db.execute('SELECT id FROM profiles WHERE id=? AND is_active=1', (peer_id,)).fetchone()
        if not peer: raise HTTPException(404, 'Профиль недоступен')
        active = db.execute("SELECT 1 FROM call_sessions WHERE state IN ('ringing','accepted') AND expires>? AND (caller_id IN (?,?) OR callee_id IN (?,?)) LIMIT 1", (now, me['id'],peer_id,me['id'],peer_id)).fetchone()
        if active: raise HTTPException(409, 'У одного из участников уже идёт звонок')
        db.execute('DELETE FROM call_sessions WHERE created<?', (now - 30 * 86400,))
        db.execute('INSERT INTO call_sessions(id,caller_id,callee_id,state,created,updated,expires) VALUES(?,?,?,\'ringing\',?,?,?)', (call_id,me['id'],peer_id,now,now,now+45))
        db.execute('INSERT INTO call_push_jobs(call_id) VALUES(?)', (call_id,))
        caller_name = me['name']
    publish_realtime(peer_id, {'type':'call_invite','peer_id':me['id'],'call_id':call_id,'caller_name':caller_name,'expires_at':now+45})
    dispatcher = getattr(app.state, 'push', None)
    if dispatcher: dispatcher.wake.set()
    return {'call_id':call_id,'state':'ringing','expires_at':now+45}

@app.post('/calls/{call_id}/state')
def update_call(call_id: str, body: CallStateInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    try: call_id = str(UUID(call_id))
    except ValueError: raise HTTPException(404, 'Звонок не найден')
    now = time.time()
    with connect() as db:
        call = db.execute('SELECT * FROM call_sessions WHERE id=?', (call_id,)).fetchone()
        if not call or me['id'] not in (call['caller_id'], call['callee_id']): raise HTTPException(404, 'Звонок не найден')
        state = call['state']; action = body.action
        if state == 'ringing' and call['expires'] <= now: state = 'missed'
        if action == 'accept' and me['id'] == call['callee_id'] and state == 'ringing': state = 'accepted'
        elif action == 'decline' and me['id'] == call['callee_id'] and state == 'ringing': state = 'declined'
        elif action == 'cancel' and me['id'] == call['caller_id'] and state == 'ringing': state = 'cancelled'
        elif action == 'end' and state in ('accepted','ringing'): state = 'ended'
        elif action == 'missed' and me['id'] == call['callee_id'] and state == 'missed': state = 'missed'
        else: raise HTTPException(409, 'Это действие уже недоступно')
        db.execute('UPDATE call_sessions SET state=?,updated=?,ended_by=? WHERE id=?', (state,now,me['id'] if state in ('declined','cancelled','ended','missed') else None,call_id))
        peer_id = call['callee_id'] if me['id'] == call['caller_id'] else call['caller_id']
    publish_realtime(peer_id, {'type':'call_state','peer_id':me['id'],'call_id':call_id,'state':state})
    return {'call_id':call_id,'state':state}

@app.post('/calls/{call_id}/signal')
def signal_call(call_id: str, body: CallSignalInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    try: call_id = str(UUID(call_id))
    except ValueError: raise HTTPException(404, 'Звонок не найден')
    import json
    if len(json.dumps(body.payload, separators=(',',':'))) > 12000: raise HTTPException(413, 'Сигнал слишком большой')
    with connect() as db:
        call = db.execute('SELECT * FROM call_sessions WHERE id=?', (call_id,)).fetchone()
        if not call or me['id'] not in (call['caller_id'],call['callee_id']): raise HTTPException(404, 'Звонок не найден')
        if call['expires'] < time.time() or call['state'] not in ('ringing','accepted'): raise HTTPException(409, 'Звонок уже завершён')
        if body.kind == 'offer' and (me['id'] != call['caller_id'] or call['state'] not in ('ringing','accepted')): raise HTTPException(403, 'Offer разрешён только звонящему')
        if body.kind == 'answer' and (me['id'] != call['callee_id'] or call['state'] != 'accepted'): raise HTTPException(403, 'Answer разрешён только принявшему звонок')
        peer_id = call['callee_id'] if me['id'] == call['caller_id'] else call['caller_id']
    publish_realtime(peer_id, {'type':'call_signal','peer_id':me['id'],'call_id':call_id,'kind':body.kind,'payload':body.payload})
    return {'relayed': True}

@app.get('/calls/missed')
def missed_calls(authorization: str | None = Header(default=None)):
    me = current_profile(authorization); now = time.time()
    with connect() as db:
        db.execute("UPDATE call_sessions SET state='missed',updated=? WHERE callee_id=? AND state='ringing' AND expires<=?", (now,me['id'],now))
        count = db.execute("SELECT COUNT(*) AS count FROM call_sessions WHERE callee_id=? AND state='missed' AND seen_at IS NULL", (me['id'],)).fetchone()['count']
        db.execute("UPDATE call_sessions SET seen_at=? WHERE callee_id=? AND state='missed' AND seen_at IS NULL", (now,me['id']))
    return {'unread_count':count}

@app.put('/push-token')
def register_push(body: PushTokenInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    with connect() as db:
        if not body.enabled:
            db.execute('DELETE FROM push_tokens WHERE profile_id=?', (me['id'],))
        else:
            try:
                db.execute('INSERT INTO push_tokens(profile_id,token,updated) VALUES(?,?,?) ON CONFLICT(profile_id) DO UPDATE SET token=excluded.token,updated=excluded.updated',
                           (me['id'], body.token, time.time()))
            except INTEGRITY_ERRORS:
                raise HTTPException(409, 'Этот токен уже связан с другим профилем')
    return {'registered': body.enabled}

@app.get('/profiles/{peer_id}')
def get_profile(peer_id: str, authorization: str | None = Header(default=None)):
    current_profile(authorization)
    cache = redis_sync
    if cache is not None:
        try:
            cached = cache.get('salom:cache:profile:' + peer_id)
            if cached: return with_presence(json.loads(cached))
        except Exception:
            pass
    with connect() as db:
        row = db.execute('SELECT * FROM profiles WHERE id=? AND is_active=1', (peer_id,)).fetchone()
        if not row: raise HTTPException(404, 'Профиль недоступен')
        result = dict(row)
        if cache is not None:
            try: cache.setex('salom:cache:profile:' + peer_id, 20, json.dumps({key: result.get(key) for key in ('id','name','username','is_active','avatar_url','online_at')}, separators=(',', ':')))
            except Exception: pass
        return with_presence(result)

@app.post('/presence')
def heartbeat(authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    now = time.time()
    with connect() as db:
        db.execute('UPDATE profiles SET online_at=? WHERE id=?', (now, me['id']))
    if redis_sync is not None:
        try: redis_sync.setex('salom:presence:' + me['id'], 45, str(now))
        except Exception: pass
    return {'online': True, 'at': now}

class TypingInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    peer_id: str = Field(min_length=36, max_length=36)
    typing: bool

@app.post('/typing')
def set_typing(body: TypingInput, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    try: UUID(body.peer_id)
    except ValueError: raise HTTPException(400, 'Неверный собеседник')
    if body.peer_id == me['id']: raise HTTPException(400, 'Нельзя печатать самому себе')
    with connect() as db:
        if body.typing and not db.execute('SELECT 1 FROM profiles WHERE id=? AND is_active=1', (body.peer_id,)).fetchone():
            raise HTTPException(404, 'Собеседник недоступен')
        db.execute('UPDATE profiles SET typing_to=?,typing_at=? WHERE id=?', (body.peer_id if body.typing else None, time.time() if body.typing else 0, me['id']))
    publish_realtime(body.peer_id, {'type': 'typing', 'peer_id': me['id'], 'typing': body.typing})
    return {'typing': body.typing}

@app.get('/typing')
def get_typing(peer_id: str, authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    try: UUID(peer_id)
    except ValueError: raise HTTPException(400, 'Неверный собеседник')
    with connect() as db:
        row = db.execute('SELECT typing_to,typing_at FROM profiles WHERE id=? AND is_active=1', (peer_id,)).fetchone()
        return {'typing': bool(row and row['typing_to'] == me['id'] and row['typing_at'] >= time.time() - 6)}

@app.get('/messages')
def get_messages(peer_id: str, after: int = Query(default=0, ge=0), receipts_after: int = Query(default=0, ge=0), events_after: int = Query(default=0, ge=0), authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    with connect() as db:
        cleared = media.cutoff(db, me['id'], peer_id)
        now = time.time()
        db.execute('UPDATE messages SET delivered_at=COALESCE(delivered_at,?) WHERE sender_id=? AND recipient_id=? AND id>?', (now, peer_id, me['id'], cleared))
        receipt_cursor = db.execute('UPDATE messages SET read_at=COALESCE(read_at,?) WHERE sender_id=? AND recipient_id=? AND id>? AND read_at IS NULL', (now, peer_id, me['id'], cleared))
        rows = db.execute('''SELECT * FROM messages WHERE id>? AND NOT EXISTS(SELECT 1 FROM message_hides h WHERE h.owner_id=? AND h.message_id=messages.id) AND
          ((sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?))
          ORDER BY id LIMIT 100''', (max(after, cleared), me['id'], me['id'], peer_id, peer_id, me['id'])).fetchall()
        receipts = db.execute('''SELECT id, CASE WHEN read_at IS NOT NULL THEN 'read' WHEN delivered_at IS NOT NULL THEN 'delivered' ELSE 'sent' END AS receipt
          FROM messages WHERE sender_id=? AND recipient_id=? AND id>? ORDER BY id''', (me['id'], peer_id, max(receipts_after, cleared))).fetchall()
        events = db.execute('''SELECT * FROM message_events WHERE id>? AND ((sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)) AND (owner_id IS NULL OR owner_id=?) ORDER BY id LIMIT 300''', (events_after, me['id'],peer_id,peer_id,me['id'],me['id'])).fetchall()
        changes = []
        for event in events:
            change = {'id': event['id'], 'message_id': event['message_id'], 'kind': event['kind']}
            if event['kind'] == 'edit':
                current = db.execute('SELECT * FROM messages WHERE id=?',(event['message_id'],)).fetchone()
                if current: change['message'] = message_json(current,db)
            changes.append(change)
        payload = {'messages': [message_json(row, db) for row in rows], 'receipts': [{'id': row['id'], 'receipt': row['receipt']} for row in receipts], 'events': changes, 'has_more': len(rows) == 100, 'cleared_through': cleared}
    if receipt_cursor.rowcount:
        publish_realtime(peer_id, {'type': 'sync', 'peer_id': me['id']})
    return payload

@app.get('/conversations')
def conversations(authorization: str | None = Header(default=None)):
    me = current_profile(authorization)
    with connect() as db:
        now = time.time()
        db.execute('''UPDATE messages SET delivered_at=COALESCE(delivered_at,?) WHERE recipient_id=?
          AND id>COALESCE((SELECT through_id FROM conversation_clears c WHERE c.owner_id=? AND c.peer_id=messages.sender_id),0)''', (now, me['id'], me['id']))
        rows = db.execute('''WITH latest AS (
          SELECT CASE WHEN sender_id=? THEN recipient_id ELSE sender_id END AS peer_id, MAX(id) AS last_id
          FROM messages WHERE (sender_id=? OR recipient_id=?) AND id>COALESCE((SELECT through_id FROM conversation_clears c WHERE c.owner_id=? AND c.peer_id=CASE WHEN sender_id=? THEN recipient_id ELSE sender_id END),0) AND NOT EXISTS(SELECT 1 FROM message_hides h WHERE h.owner_id=? AND h.message_id=messages.id) GROUP BY peer_id ORDER BY last_id DESC LIMIT 100)
          SELECT p.*, COALESCE(NULLIF(m.text,''),CASE WHEN a.kind='voice' THEN 'Голосовое сообщение' ELSE '📎 Вложение' END) AS last_text, m.id AS last_message_id FROM latest l
          JOIN profiles p ON p.id=l.peer_id JOIN messages m ON m.id=l.last_id LEFT JOIN attachments a ON a.id=m.attachment_id ORDER BY l.last_id DESC''', (me['id'], me['id'], me['id'], me['id'], me['id'], me['id'])).fetchall()
        return {'conversations': [{'profile': public(row), 'last_text': row['last_text'], 'last_message_id': row['last_message_id']} for row in rows]}
