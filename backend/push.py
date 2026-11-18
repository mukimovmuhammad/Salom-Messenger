"""Durable FCM HTTP v1 delivery. No credentials or device tokens are logged."""
import json
import logging
import os
from pathlib import Path
import threading
import time
import media

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

log = logging.getLogger('salom.push')

def payload(token, message, sender):
    text = message['text'] or '📎 Вложение'
    return {'message': {
        'token': token,
        'notification': {'title': sender['name'], 'body': text[:180] + ('…' if len(text) > 180 else '')},
        'data': {'peer_id': sender['id'], 'message_id': str(message['id'])},
        'android': {'priority': 'HIGH', 'ttl': '86400s', 'restricted_package_name': 'com.mukimov.salom',
                    'notification': {'channel_id': 'salom_messages_ding_v1', 'icon': 'ic_notification',
                    'color': '#FFC400', 'sound': 'salom_ding', 'tag': 'salom-peer-' + sender['id'],
                                     'visibility': 'PRIVATE'}},
    }}

class PushDispatcher:
    def __init__(self, connect):
        self.connect = connect
        self.stop_event = threading.Event()
        self.wake = threading.Event()
        self.thread = None
        self.session = None
        self.project = None
        credential = Path(os.environ.get('GOOGLE_APPLICATION_CREDENTIALS', '/etc/salom-api/firebase-admin.json'))
        if credential.is_file():
            credentials = service_account.Credentials.from_service_account_file(
                str(credential), scopes=['https://www.googleapis.com/auth/firebase.messaging'])
            self.project = credentials.project_id
            self.session = AuthorizedSession(credentials)

    @property
    def configured(self): return self.session is not None

    def start(self):
        if self.configured:
            self.thread = threading.Thread(target=self.run, name='salom-push', daemon=True)
            self.thread.start()

    def stop(self):
        self.stop_event.set()
        self.wake.set()
        if self.thread: self.thread.join(timeout=12)

    def run(self):
        while not self.stop_event.is_set():
            try:
                if self.deliver_one(): continue
            except Exception:
                # Details can contain an endpoint token; log only the class of operation.
                log.warning('Push dispatcher iteration failed; will retry')
            self.wake.wait(2)
            self.wake.clear()

    def deliver_one(self):
        if self.deliver_call_one(): return True
        with self.connect() as db:
            job = db.execute('SELECT * FROM push_jobs WHERE state=? AND next_at<=? ORDER BY message_id LIMIT 1', ('pending', time.time())).fetchone()
            if not job: return False
            message = db.execute('SELECT * FROM messages WHERE id=?', (job['message_id'],)).fetchone()
            sender = db.execute('SELECT * FROM profiles WHERE id=?', (message['sender_id'],)).fetchone()
            target = db.execute('SELECT token FROM push_tokens WHERE profile_id=?', (message['recipient_id'],)).fetchone()
            if not target or message['id'] <= media.cutoff(db, message['recipient_id'], message['sender_id']) or time.time() - message['created'] > 86400:
                db.execute("UPDATE push_jobs SET state='skipped' WHERE message_id=?", (message['id'],))
                return True
            if message['attachment_id'] and not message['text']:
                attachment = media.metadata(db, message['attachment_id'])
                if attachment and attachment['kind'] == 'voice':
                    message = dict(message)
                    message['text'] = 'Голосовое сообщение'
            token = target['token']
        code = 'NETWORK'
        permanent = False
        invalid_token = False
        try:
            response = self.session.post('https://fcm.googleapis.com/v1/projects/' + self.project + '/messages:send',
                                         json=payload(token, message, sender), timeout=8)
            if response.status_code == 200:
                with self.connect() as db:
                    db.execute("UPDATE push_jobs SET state='sent',last_error=NULL WHERE message_id=?", (message['id'],))
                return True
            error = response.json().get('error', {})
            codes = [detail.get('errorCode') for detail in error.get('details', []) if isinstance(detail, dict)]
            invalid_token = 'UNREGISTERED' in codes
            code = str(response.status_code) + ':' + str(error.get('status', 'UNKNOWN'))
            permanent = invalid_token or response.status_code in (400, 404)
        except Exception:
            pass
        attempts = job['attempts'] + 1
        with self.connect() as db:
            if invalid_token:
                db.execute('DELETE FROM push_tokens WHERE profile_id=? AND token=?', (message['recipient_id'], token))
            db.execute('UPDATE push_jobs SET state=?,attempts=?,next_at=?,last_error=? WHERE message_id=?',
                       ('failed' if permanent or attempts >= 8 else 'pending', attempts,
                        time.time() + min(5 * 3 ** (attempts - 1), 3600), code, message['id']))
        log.warning('FCM delivery not accepted (%s), attempt %d', code, attempts)
        return True

    def deliver_call_one(self):
        with self.connect() as db:
            now = time.time()
            expired = db.execute("SELECT c.id FROM call_sessions c JOIN call_push_jobs j ON j.call_id=c.id WHERE c.state='ringing' AND c.expires<=? AND j.state='sent' ORDER BY c.expires LIMIT 1", (now,)).fetchone()
            if expired:
                db.execute("UPDATE call_sessions SET state='missed',updated=? WHERE id=? AND state='ringing'", (now,expired['id']))
                db.execute("UPDATE call_push_jobs SET state='missed_pending',attempts=0,next_at=0 WHERE call_id=?", (expired['id'],))
            job = db.execute("SELECT * FROM call_push_jobs WHERE state IN ('pending','missed_pending') AND next_at<=? ORDER BY next_at LIMIT 1", (now,)).fetchone()
            if not job: return False
            call = db.execute('SELECT * FROM call_sessions WHERE id=?', (job['call_id'],)).fetchone()
            missed = job['state'] == 'missed_pending'
            if not call or (not missed and (call['state'] != 'ringing' or call['expires'] <= now)):
                db.execute("UPDATE call_push_jobs SET state='skipped' WHERE call_id=?", (job['call_id'],))
                return True
            target = db.execute('SELECT token FROM push_tokens WHERE profile_id=?', (call['callee_id'],)).fetchone()
            caller = db.execute('SELECT name FROM profiles WHERE id=?', (call['caller_id'],)).fetchone()
            if not target or not caller:
                db.execute("UPDATE call_push_jobs SET state='skipped' WHERE call_id=?", (job['call_id'],))
                return True
            token = target['token']
            if missed:
                count = db.execute("SELECT COUNT(*) AS count FROM call_sessions WHERE callee_id=? AND state='missed' AND seen_at IS NULL", (call['callee_id'],)).fetchone()['count']
                data = {'type':'missed_call','call_id':call['id'],'caller_id':call['caller_id'],'caller_name':caller['name'][:80],'unread_count':str(count)}
            else:
                data = {'type': 'incoming_call', 'call_id': call['id'], 'caller_id': call['caller_id'], 'caller_name': caller['name'][:80]}
        code = 'NETWORK'
        permanent = False
        invalid_token = False
        try:
            response = self.session.post('https://fcm.googleapis.com/v1/projects/' + self.project + '/messages:send',
                json={'message': {'token': token, 'data': data,
                    'android': {'priority': 'HIGH', 'ttl': '45s' if not missed else '86400s', 'restricted_package_name': 'com.mukimov.salom'}}}, timeout=8)
            if response.status_code == 200:
                with self.connect() as db: db.execute("UPDATE call_push_jobs SET state=?,last_error=NULL WHERE call_id=?", ('missed_sent' if missed else 'sent',call['id']))
                return True
            error = response.json().get('error', {})
            codes = [detail.get('errorCode') for detail in error.get('details', []) if isinstance(detail, dict)]
            invalid_token = 'UNREGISTERED' in codes
            code = str(response.status_code) + ':' + str(error.get('status', 'UNKNOWN'))
            permanent = invalid_token or response.status_code in (400, 404)
        except Exception:
            pass
        attempts = job['attempts'] + 1
        with self.connect() as db:
            if invalid_token: db.execute('DELETE FROM push_tokens WHERE profile_id=? AND token=?', (call['callee_id'], token))
            db.execute('UPDATE call_push_jobs SET state=?,attempts=?,next_at=?,last_error=? WHERE call_id=?',
                ('failed' if permanent or attempts >= 3 else ('missed_pending' if missed else 'pending'), attempts,
                 time.time() + min(2 ** attempts, 8), code, call['id']))
        log.warning('FCM call push not accepted (%s), attempt %d', code, attempts)
        return True
