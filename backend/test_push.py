"""Isolated SQLite tests: no network, no production data or credentials."""
import hashlib
import os
from pathlib import Path
import tempfile
import time
import types
import unittest
import uuid

import app
from push import PushDispatcher

class Response:
    def __init__(self, status, data=None): self.status_code, self.data = status, data or {}
    def json(self): return self.data

class Session:
    def __init__(self, response): self.response, self.sent = response, []
    def post(self, url, **kwargs):
        self.sent.append(kwargs['json'])
        if isinstance(self.response, Exception): raise self.response
        return self.response

class PushTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        app.DB = Path(self.tmp.name) / 'test.sqlite3'
        app.initialize()
        os.environ['GOOGLE_APPLICATION_CREDENTIALS'] = str(Path(self.tmp.name) / 'no-key.json')
        self.dispatcher = PushDispatcher(app.connect)
        app.app.state.push = self.dispatcher
        self.ids = [str(uuid.uuid4()), str(uuid.uuid4())]
        self.tokens = ['A' * 43, 'B' * 43]
        with app.connect() as db:
            for index in range(2):
                db.execute('INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)', (self.ids[index], hashlib.sha256(self.tokens[index].encode()).hexdigest(), 'QA', 'qa' + str(index), 'qa' + str(index), time.time(), time.time()))
        app.register_push(app.PushTokenInput(token='fcm_' + 'x' * 64), 'Bearer ' + self.tokens[1])

    def tearDown(self): self.tmp.cleanup()

    def send(self):
        body = app.MessageInput(recipient_id=self.ids[1], client_id=str(uuid.uuid4()), text='Салом 💛')
        result = app.send_message(body, 'Bearer ' + self.tokens[0])
        self.assertEqual(app.send_message(body, 'Bearer ' + self.tokens[0])['id'], result['id'])
        with app.connect() as db: self.assertEqual(db.execute('SELECT count(*) FROM push_jobs').fetchone()[0], 1)
        return result

    def test_delivery_and_idempotent_outbox(self):
        result = self.send()
        self.dispatcher.session = Session(Response(200))
        self.dispatcher.project = 'qa-project'
        self.assertTrue(self.dispatcher.deliver_one())
        sent = self.dispatcher.session.sent[0]['message']
        self.assertEqual(sent['data']['peer_id'], self.ids[0])
        self.assertEqual(sent['data']['message_id'], str(result['id']))
        self.assertNotIn('notification', sent)
        self.assertNotIn('notification', sent['android'])
        self.assertIn('title', sent['data'])
        self.assertIn('body', sent['data'])
        with app.connect() as db: self.assertEqual(db.execute('SELECT state FROM push_jobs').fetchone()[0], 'sent')

    def test_network_failure_retries_without_losing_message(self):
        self.send()
        self.dispatcher.session, self.dispatcher.project = Session(OSError('offline')), 'qa'
        self.dispatcher.deliver_one()
        with app.connect() as db:
            row = db.execute('SELECT * FROM push_jobs').fetchone()
            self.assertEqual((row['state'], row['attempts']), ('pending', 1))
            self.assertGreater(row['next_at'], time.time())
            self.assertEqual(db.execute('SELECT count(*) FROM messages').fetchone()[0], 1)

    def test_unregistered_token_removed(self):
        self.send()
        error = {'error': {'status': 'NOT_FOUND', 'details': [{'errorCode': 'UNREGISTERED'}]}}
        self.dispatcher.session, self.dispatcher.project = Session(Response(404, error)), 'qa'
        self.dispatcher.deliver_one()
        with app.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM push_tokens').fetchone()[0], 0)
            self.assertEqual(db.execute('SELECT state FROM push_jobs').fetchone()[0], 'failed')

    def test_disabled_registration_skips_queued_push(self):
        self.send()
        app.register_push(app.PushTokenInput(token='fcm_' + 'x' * 64, enabled=False), 'Bearer ' + self.tokens[1])
        self.assertTrue(self.dispatcher.deliver_one())
        with app.connect() as db: self.assertEqual(db.execute('SELECT state FROM push_jobs').fetchone()[0], 'skipped')

    def test_foreign_token_binding_rejected(self):
        with self.assertRaises(app.HTTPException) as raised:
            app.register_push(app.PushTokenInput(token='fcm_' + 'x' * 64), 'Bearer ' + self.tokens[0])
        self.assertEqual(raised.exception.status_code, 409)

    def test_cleared_recipient_skips_queued_push(self):
        message = self.send()
        with app.connect() as db:
            db.execute('INSERT INTO conversation_clears VALUES(?,?,?)', (self.ids[1], self.ids[0], message['id']))
        self.dispatcher.session = Session(Response(200))
        self.dispatcher.project = 'qa-project'
        self.assertTrue(self.dispatcher.deliver_one())
        self.assertEqual(self.dispatcher.session.sent, [])
        with app.connect() as db: self.assertEqual(db.execute('SELECT state FROM push_jobs').fetchone()[0], 'skipped')

    def test_voice_notification_sends_label_to_client_for_display(self):
        attachment=str(uuid.uuid4())
        with app.connect() as db:
            db.execute("INSERT INTO attachments(id,owner_id,peer_id,name,mime,size,created,ready,kind,duration_ms,waveform) VALUES(?,?,?,?,?,?,?,1,'voice',1200,'[2,30,5]')", (attachment,self.ids[0],self.ids[1],'voice.m4a','audio/mp4',12,time.time()))
        app.send_message(app.MessageInput(recipient_id=self.ids[1],client_id=str(uuid.uuid4()),attachment_id=attachment),'Bearer '+self.tokens[0])
        self.dispatcher.session=Session(Response(200));self.dispatcher.project='qa-project'
        self.assertTrue(self.dispatcher.deliver_one())
        notification=self.dispatcher.session.sent[0]['message']
        self.assertEqual(notification['data']['body'],'Голосовое сообщение')
        self.assertNotIn('notification', notification['android'])

if __name__ == '__main__': unittest.main()
