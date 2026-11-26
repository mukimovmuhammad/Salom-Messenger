"""Google-only verified identities and revocable Salom device sessions."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
import uuid
from urllib.parse import urlparse

from cachecontrol import CacheControl
from fastapi import Header, HTTPException, Query, Request
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2 import id_token
from pydantic import BaseModel, ConfigDict, Field
import requests
from database import INTEGRITY_ERRORS

USERNAME = re.compile(r'^[A-Za-z][A-Za-z0-9_]{3,23}$')
EMAIL = re.compile(r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
firebase_request = GoogleRequest(session=CacheControl(requests.Session()))
firebase_lock = threading.Lock()
firebase_admin_lock = threading.Lock()
firebase_admin_app = None

def schema(db):
    # Keep previous columns intact so upgrading never discards existing identities.
    db.executescript('''CREATE TABLE IF NOT EXISTS email_identities (profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE);
      CREATE TABLE IF NOT EXISTS accounts (
        profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
        firebase_uid TEXT UNIQUE, phone TEXT UNIQUE, email TEXT UNIQUE,
        password_hash TEXT, created REAL NOT NULL, password_changed REAL);
      CREATE TABLE IF NOT EXISTS sessions (
        token_hash TEXT PRIMARY KEY, profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
        created REAL NOT NULL, expires REAL NOT NULL, verified_at REAL NOT NULL);
      CREATE INDEX IF NOT EXISTS sessions_profile ON sessions(profile_id);
      CREATE TABLE IF NOT EXISTS auth_limits (key TEXT NOT NULL, at REAL NOT NULL);
      CREATE INDEX IF NOT EXISTS auth_limits_key ON auth_limits(key,at);
    ''')
    db.execute(SESSION_DEVICE_SCHEMA)
    columns = {row[1] for row in db.execute('PRAGMA table_info(accounts)')}
    if 'google_linked' not in columns:
        db.execute('ALTER TABLE accounts ADD COLUMN google_linked INTEGER NOT NULL DEFAULT 0')

SESSION_DEVICE_SCHEMA = '''CREATE TABLE IF NOT EXISTS session_devices (
  token_hash TEXT PRIMARY KEY REFERENCES sessions(token_hash) ON DELETE CASCADE,
  label TEXT NOT NULL)'''

def hash_token(value): return hashlib.sha256(value.encode()).hexdigest()

def limited(connect, key, maximum, seconds):
    now = time.time()
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        db.execute('DELETE FROM auth_limits WHERE at<?', (now - 86400,))
        count = db.execute('SELECT count(*) FROM auth_limits WHERE key=? AND at>?', (key, now - seconds)).fetchone()[0]
        if count >= maximum: raise HTTPException(429, 'Слишком много попыток. Попробуй позже')
        db.execute('INSERT INTO auth_limits VALUES(?,?)', (key, now))

def ip_key(request): return hash_token(request.client.host if request.client else 'unknown')

def firebase_identity(token):
    if not token: raise HTTPException(401, 'Сначала войди через Google')
    try:
        credential_path = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS', '/etc/salom-api/firebase-admin.json')
        project = json.loads(Path(credential_path).read_text())['project_id']
        with firebase_lock:
            claims = id_token.verify_firebase_token(token, firebase_request, audience=project)
        now = time.time()
        provider = claims.get('firebase', {}).get('sign_in_provider')
        if provider != 'google.com' and not (provider == 'custom' and claims.get('salom_google_verified') is True): raise ValueError()
        email = claims.get('email', '').strip().lower()
        if claims.get('email_verified') is not True or len(email) > 254 or not EMAIL.fullmatch(email): raise ValueError()
        if not claims.get('sub') or not 0 <= now - claims.get('auth_time', 0) <= 900: raise ValueError()
        picture = claims.get('picture', '')
        parsed = urlparse(picture) if isinstance(picture, str) else None
        avatar_url = picture if parsed and parsed.scheme == 'https' and (parsed.hostname or '').endswith('googleusercontent.com') and len(picture) < 2048 else ''
        return {'email': email, 'firebase_uid': claims['sub'], 'avatar_url': avatar_url}
    except Exception:
        raise HTTPException(401, 'Вход Google недействителен или устарел. Выбери аккаунт ещё раз')

class IdentityProof(BaseModel):
    model_config = ConfigDict(extra='forbid')
    firebase_token: str = Field(min_length=1, max_length=16384)
    name: str = Field(default='', max_length=80)
    username: str = Field(default='', max_length=24)
    bind_current: bool = False

class DesktopGoogleProof(BaseModel):
    model_config = ConfigDict(extra='forbid')
    google_id_token: str = Field(min_length=1, max_length=8192)

class EmailCodeRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    email: str = Field(min_length=3, max_length=254)

class EmailCodeProof(EmailCodeRequest):
    code: str = Field(pattern=r'^\d{6}$')

class EmailRegistration(EmailCodeRequest):
    ticket: str = Field(min_length=20, max_length=256)
    username: str = Field(min_length=4, max_length=24)
    name: str = Field(min_length=1, max_length=80)

def normalized_email(value):
    email = value.strip().lower()
    if len(email) > 254 or not EMAIL.fullmatch(email): raise HTTPException(400, 'Проверь адрес электронной почты')
    return email

def otp_digest(email, code):
    pepper = os.environ.get('SALOM_OTP_PEPPER', '')
    if len(pepper) < 32: raise HTTPException(503, 'Подтверждение email временно недоступно')
    return hmac.new(pepper.encode(), f'{email}:{code}'.encode(), hashlib.sha256).hexdigest()

def firebase_admin():
    global firebase_admin_app
    with firebase_admin_lock:
        if firebase_admin_app is None:
            import firebase_admin
            from firebase_admin import credentials
            credential_path = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS', '/etc/salom-api/firebase-admin.json')
            project = json.loads(Path(credential_path).read_text())['project_id']
            try:
                firebase_admin_app = firebase_admin.get_app('salom-desktop-auth')
            except ValueError:
                firebase_admin_app = firebase_admin.initialize_app(
                    credentials.Certificate(credential_path), {'projectId': project}, name='salom-desktop-auth')
        return firebase_admin_app

def issue_desktop_firebase_token(google_claims):
    from firebase_admin import auth as admin_auth
    app_instance = firebase_admin()
    email = google_claims['email'].strip().lower()
    try:
        user = admin_auth.get_user_by_email(email, app=app_instance)
    except admin_auth.UserNotFoundError:
        try:
            user = admin_auth.create_user(email=email, email_verified=True,
                display_name=(google_claims.get('name') or '')[:256] or None,
                photo_url=(google_claims.get('picture') or '')[:2048] or None, app=app_instance)
        except Exception:
            # Handle simultaneous first sign-in without creating a second identity.
            user = admin_auth.get_user_by_email(email, app=app_instance)
    options = {'email_verified': True}
    display_name = (google_claims.get('name') or '')[:256]
    picture = google_claims.get('picture') or ''
    parsed = urlparse(picture) if isinstance(picture, str) else None
    if display_name: options['display_name'] = display_name
    if parsed and parsed.scheme == 'https' and (parsed.hostname or '').endswith('googleusercontent.com') and len(picture) < 2048:
        options['photo_url'] = picture
    if not user.email_verified or options.get('display_name') or options.get('photo_url'):
        admin_auth.update_user(user.uid, app=app_instance, **options)
    return admin_auth.create_custom_token(user.uid, {'salom_google_verified': True}, app=app_instance).decode('utf-8')

def install_routes(app, connect, current_profile, public, digest):
    def redis_client():
        client = getattr(app.state, 'redis_sync', None)
        if client is None: raise HTTPException(503, 'Сервис временно недоступен')
        return client
    def account_json(db, profile_id):
        row = db.execute('SELECT * FROM accounts WHERE profile_id=?', (profile_id,)).fetchone()
        linked = bool(row and row['google_linked'])
        email_verified = bool(db.execute('SELECT 1 FROM email_identities WHERE profile_id=?', (profile_id,)).fetchone())
        return {'verified': linked or email_verified, 'google_linked': linked, 'email': (row['email'] or '') if row else ''}

    def session(db, profile_id, request):
        profile = db.execute('SELECT * FROM profiles WHERE id=?', (profile_id,)).fetchone()
        if not profile or not profile['is_active']: raise HTTPException(403, 'Аккаунт временно недоступен')
        token, now = secrets.token_urlsafe(32), time.time()
        db.execute('DELETE FROM sessions WHERE expires<?', (now,))
        db.execute('INSERT INTO sessions VALUES(?,?,?,?,?)', (hash_token(token), profile_id, now, now + 90 * 86400, now))
        save_device(db, hash_token(token), request)
        return {'profile': public(profile), 'session_token': token, **account_json(db, profile_id)}

    def save_device(db, token_hash, request):
        label = request.headers.get('x-salom-device', '').strip()[:72]
        if re.fullmatch(r'(?:Android|Windows)(?: · [A-Za-z0-9 ._()+-]{1,55})?', label.replace(' / ', ' · ')):
            db.execute('INSERT INTO session_devices(token_hash,label) VALUES(?,?) ON CONFLICT(token_hash) DO NOTHING',
                       (token_hash, label.replace(' / ', ' · ')))

    @app.get('/auth/config')
    def config():
        return {'google_enabled': True, 'email_enabled': bool(os.environ.get('BREVO_API_KEY') and os.environ.get('BREVO_SENDER_EMAIL') and os.environ.get('SALOM_OTP_PEPPER')), 'sms_enabled': False}

    @app.post('/auth/email/code')
    def send_email_code(body: EmailCodeRequest, request: Request):
        email=normalized_email(body.email); limited(connect,'email-code-ip:'+ip_key(request),8,3600)
        if not os.environ.get('BREVO_API_KEY') or not os.environ.get('BREVO_SENDER_EMAIL'):
            raise HTTPException(503,'Отправка кодов временно не настроена')
        redis=redis_client(); key='salom:email-otp:'+hashlib.sha256(email.encode()).hexdigest()
        if not redis.set(key+':cooldown','1',nx=True,ex=60): raise HTTPException(429,'Подожди минуту перед повторной отправкой')
        code=f'{secrets.randbelow(1_000_000):06d}'
        redis.set(key,json.dumps({'digest':otp_digest(email,code),'attempts':0}),ex=300)
        try:
            response=requests.post('https://api.brevo.com/v3/smtp/email',headers={'api-key':os.environ['BREVO_API_KEY'],'accept':'application/json','content-type':'application/json'},json={'sender':{'email':os.environ['BREVO_SENDER_EMAIL'],'name':'Салом'},'to':[{'email':email}],'subject':'Код подтверждения Салом','textContent':f'Код подтверждения: {code}. Он действует 5 минут. Не сообщай его никому.'},timeout=8)
            response.raise_for_status()
        except requests.RequestException:
            redis.delete(key,key+':cooldown')
            raise HTTPException(502,'Не удалось отправить письмо. Проверь адрес и попробуй позже')
        return {'sent':True,'expires_in':300,'resend_after':60}

    @app.post('/auth/email/verify')
    def verify_email_code(body: EmailCodeProof, request: Request):
        email=normalized_email(body.email); limited(connect,'email-verify-ip:'+ip_key(request),30,3600)
        redis=redis_client(); key='salom:email-otp:'+hashlib.sha256(email.encode()).hexdigest()
        result=redis.eval("""
local raw=redis.call('GET',KEYS[1]); if not raw then return -1 end
local data=cjson.decode(raw)
if data.attempts>=5 then return -2 end
if data.digest~=ARGV[1] then
 data.attempts=data.attempts+1
 redis.call('SET',KEYS[1],cjson.encode(data),'KEEPTTL'); return 0
end
redis.call('DEL',KEYS[1]); return 1
""",1,key,otp_digest(email,body.code))
        if result==-1: raise HTTPException(400,'Код истёк. Запроси новый')
        if result==-2: raise HTTPException(429,'Попытки закончились. Запроси новый код')
        if result!=1: raise HTTPException(400,'Код неверный')
        with connect() as db:
            existing=db.execute('SELECT * FROM accounts WHERE email=?',(email,)).fetchone()
            if existing: return session(db,existing['profile_id'], request)
        ticket=secrets.token_urlsafe(32)
        redis.set('salom:email-ticket:'+hashlib.sha256(ticket.encode()).hexdigest(),email,ex=300)
        return {'needs_username':True,'ticket':ticket,'expires_in':300}

    @app.post('/auth/email/register')
    def register_email(body: EmailRegistration, request: Request):
        email=normalized_email(body.email); limited(connect,'email-register-ip:'+ip_key(request),10,3600)
        username=body.username.strip()
        if not USERNAME.fullmatch(username): raise HTTPException(400,'Username: 4–24 латинские буквы, цифры и _, начинается с буквы')
        redis=redis_client(); ticket_key='salom:email-ticket:'+hashlib.sha256(body.ticket.encode()).hexdigest()
        if redis.get(ticket_key)!=email: raise HTTPException(400,'Подтверждение email истекло. Запроси новый код')
        now=time.time()
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            existing=db.execute('SELECT * FROM accounts WHERE email=?',(email,)).fetchone()
            if existing:
                target=existing['profile_id']
            else:
                target=str(uuid.uuid4())
                try:
                    db.execute('INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)',(target,hash_token(secrets.token_urlsafe(32)),body.name.strip(),username,username.lower(),now,now))
                    db.execute('INSERT INTO accounts(profile_id,email,created) VALUES(?,?,?)',(target,email,now))
                except INTEGRITY_ERRORS: raise HTTPException(409,'Этот username уже занят. Выбери другой')
            if redis.getdel(ticket_key)!=email: raise HTTPException(400,'Подтверждение уже использовано. Запроси новый код')
            db.execute('INSERT INTO email_identities(profile_id) VALUES(?) ON CONFLICT(profile_id) DO NOTHING',(target,))
            db.execute('UPDATE profiles SET token_hash=? WHERE id=?',(hash_token(secrets.token_urlsafe(32)),target))
            return session(db,target, request)

    @app.post('/auth/desktop/custom-token')
    def desktop_custom_token(proof: DesktopGoogleProof, request: Request):
        limited(connect, 'desktop-google:' + ip_key(request), 20, 60)
        client_id = os.environ.get('SALOM_DESKTOP_GOOGLE_CLIENT_ID', '').strip()
        if not client_id: raise HTTPException(503, 'Вход с компьютера временно недоступен')
        try:
            claims = id_token.verify_oauth2_token(proof.google_id_token, firebase_request, audience=client_id)
            email = claims.get('email', '').strip().lower()
            if claims.get('email_verified') is not True or len(email) > 254 or not EMAIL.fullmatch(email) or not claims.get('sub'):
                raise ValueError()
            firebase_custom_token = issue_desktop_firebase_token(claims)
            return {'firebase_custom_token': firebase_custom_token}
        except Exception:
            raise HTTPException(401, 'Вход Google недействителен. Выбери аккаунт ещё раз')

    @app.get('/auth/username')
    def username_available(request: Request, username: str = Query(min_length=1, max_length=24)):
        limited(connect, 'username:' + ip_key(request), 180, 60)
        if not USERNAME.fullmatch(username): return {'available': False, 'valid': False}
        with connect() as db:
            found = db.execute('SELECT 1 FROM profiles WHERE username_key=?', (username.lower(),)).fetchone()
        return {'available': not bool(found), 'valid': True}

    @app.post('/auth/identity')
    def exchange(proof: IdentityProof, request: Request, authorization: str | None = Header(default=None)):
        limited(connect, 'identity-ip:' + ip_key(request), 30, 60)
        contact = firebase_identity(proof.firebase_token)
        owner = None
        if proof.bind_current:
            try: owner = current_profile(authorization)
            except HTTPException as error:
                if error.status_code != 401: raise
        now = time.time()
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            existing = db.execute('SELECT * FROM accounts WHERE firebase_uid=?', (contact['firebase_uid'],)).fetchone()
            email_owner = db.execute('SELECT * FROM accounts WHERE email=?', (contact['email'],)).fetchone()
            # Never merge different Firebase identities on a mutable email claim.
            if email_owner and email_owner['firebase_uid'] not in (None, contact['firebase_uid']):
                raise HTTPException(409, 'Этот email уже связан с другим аккаунтом Салом')
            if not owner and not existing and email_owner and email_owner['firebase_uid'] is None:
                verified_email=db.execute('SELECT 1 FROM email_identities WHERE profile_id=?',(email_owner['profile_id'],)).fetchone()
                if not verified_email: raise HTTPException(409, 'Сначала подключи Google из существующего профиля Салом')
                db.execute('UPDATE accounts SET firebase_uid=?,google_linked=1 WHERE profile_id=?',(contact['firebase_uid'],email_owner['profile_id']))
                return session(db,email_owner['profile_id'], request)
            if owner:
                target = owner['id']
                valid_owner = db.execute('SELECT 1 FROM profiles p WHERE p.id=? AND p.is_active=1 AND (p.token_hash=? OR EXISTS(SELECT 1 FROM sessions s WHERE s.profile_id=p.id AND s.token_hash=? AND s.expires>?))',
                                        (target, digest(authorization), digest(authorization), now)).fetchone()
                if not valid_owner: raise HTTPException(401, 'Сессия истекла. Войди через Google заново')
                if existing and existing['profile_id'] != target: raise HTTPException(409, 'Этот Google-аккаунт уже подключён к другому профилю Салом')
                if email_owner and email_owner['profile_id'] != target: raise HTTPException(409, 'Этот email уже связан с другим профилем')
                old = db.execute('SELECT * FROM accounts WHERE profile_id=?', (target,)).fetchone()
                if old and old['firebase_uid'] and old['firebase_uid'] != contact['firebase_uid']:
                    raise HTTPException(409, 'К профилю уже подключён другой аккаунт Google')
                if not owner['username']:
                    if not proof.username: return {'needs_username': True}
                    if not USERNAME.fullmatch(proof.username): raise HTTPException(400, 'Проверь username')
                    try: db.execute('UPDATE profiles SET username=?,username_key=?,updated=? WHERE id=?', (proof.username, proof.username.lower(), now, target))
                    except INTEGRITY_ERRORS: raise HTTPException(409, 'Этот username уже занят. Выбери другой')
            elif existing:
                if contact['avatar_url']:
                    db.execute('UPDATE profiles SET avatar_url=? WHERE id=? AND NOT EXISTS (SELECT 1 FROM profile_avatars WHERE owner_id=profiles.id)', (contact['avatar_url'], existing['profile_id']))
                if not existing['google_linked']:
                    db.execute('UPDATE accounts SET google_linked=1,email=? WHERE profile_id=?', (contact['email'], existing['profile_id']))
                else:
                    db.execute('UPDATE accounts SET email=? WHERE profile_id=?', (contact['email'], existing['profile_id']))
                return session(db, existing['profile_id'], request)
            else:
                if proof.bind_current: raise HTTPException(401, 'Сессия истекла. Открой сохранённый профиль и повтори подключение')
                if not proof.username: return {'needs_username': True}
                if not USERNAME.fullmatch(proof.username): raise HTTPException(400, 'Username: 4–24 латинские буквы, цифры и _, начинается с буквы')
                name = proof.name.strip()
                if not name: raise HTTPException(400, 'Укажи имя')
                target = str(uuid.uuid4())
                try:
                    db.execute('INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)', (target, hash_token(secrets.token_urlsafe(32)), name, proof.username, proof.username.lower(), now, now))
                except INTEGRITY_ERRORS: raise HTTPException(409, 'Этот username уже занят. Выбери другой')
            try:
                if contact['avatar_url']:
                    db.execute('UPDATE profiles SET avatar_url=? WHERE id=? AND NOT EXISTS (SELECT 1 FROM profile_avatars WHERE owner_id=profiles.id)', (contact['avatar_url'], target))
                db.execute('INSERT INTO accounts(profile_id,firebase_uid,email,created,google_linked) VALUES(?,?,?,?,1) ON CONFLICT(profile_id) DO UPDATE SET firebase_uid=excluded.firebase_uid,email=excluded.email,google_linked=1',
                           (target, contact['firebase_uid'], contact['email'], now))
            except INTEGRITY_ERRORS: raise HTTPException(409, 'Google уже связан с другим аккаунтом')
            # Upgrade ownership without changing profile ID or its message history.
            db.execute('UPDATE profiles SET token_hash=? WHERE id=?', (hash_token(secrets.token_urlsafe(32)), target))
            return session(db, target, request)

    @app.get('/auth/me')
    def me(authorization: str | None = Header(default=None)):
        profile = current_profile(authorization)
        with connect() as db: return account_json(db, profile['id'])

    @app.get('/auth/devices')
    def devices(request: Request, authorization: str | None = Header(default=None)):
        profile = current_profile(authorization)
        current = digest(authorization)
        with connect() as db:
            if db.execute('SELECT 1 FROM sessions WHERE profile_id=? AND token_hash=? AND expires>?',
                          (profile['id'], current, time.time())).fetchone():
                save_device(db, current, request)
            rows = db.execute('''SELECT s.token_hash,s.created,d.label FROM sessions s
                                  LEFT JOIN session_devices d ON d.token_hash=s.token_hash
                                  WHERE s.profile_id=? AND s.expires>? ORDER BY s.created DESC LIMIT 50''',
                              (profile['id'], time.time())).fetchall()
        return {'devices': [{'label': row['label'], 'created': row['created'], 'current': row['token_hash'] == current}
                            for row in rows]}

    @app.post('/auth/logout')
    def logout(authorization: str | None = Header(default=None)):
        profile = current_profile(authorization)
        with connect() as db:
            db.execute('DELETE FROM sessions WHERE token_hash=?', (digest(authorization),))
            db.execute('DELETE FROM push_tokens WHERE profile_id=?', (profile['id'],))
        return {'signed_out': True}
