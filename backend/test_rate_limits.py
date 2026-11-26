"""Authenticated message and upload rate limits using isolated data only."""
import hashlib
from pathlib import Path
import secrets
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

from fastapi.testclient import TestClient
import app
import media


class RateLimitTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_db, self.old_root = app.DB, media.ROOT
        app.DB = Path(self.tmp.name) / 'rates.sqlite3'
        media.ROOT = Path(self.tmp.name) / 'attachments'
        with app.rate_lock:
            app.rates.clear()
        self.push = patch('app.PushDispatcher')
        self.push.start()
        self.client = TestClient(app.app)
        self.client.__enter__()
        self.ids = [str(uuid.uuid4()), str(uuid.uuid4())]
        self.tokens = [secrets.token_urlsafe(32), secrets.token_urlsafe(32)]
        with app.connect() as db:
            for index in range(2):
                db.execute('''INSERT INTO profiles
                    (id,token_hash,name,username,username_key,is_active,created,updated)
                    VALUES(?,?,?,?,?,1,?,?)''',
                    (self.ids[index], hashlib.sha256(self.tokens[index].encode()).hexdigest(),
                     'Rate QA', f'rateqa{index}', f'rateqa{index}', time.time(), time.time()))

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.push.stop()
        app.DB, media.ROOT = self.old_db, self.old_root
        with app.rate_lock:
            app.rates.clear()
        self.tmp.cleanup()

    def headers(self, index=0):
        return {'Authorization': 'Bearer ' + self.tokens[index]}

    def test_message_rate_limit_returns_429(self):
        for _ in range(60):
            response = self.client.post('/messages', headers=self.headers(), json={
                'recipient_id': self.ids[1], 'client_id': str(uuid.uuid4()), 'text': 'QA'})
            self.assertEqual(response.status_code, 200, response.text)
        response = self.client.post('/messages', headers=self.headers(), json={
            'recipient_id': self.ids[1], 'client_id': str(uuid.uuid4()), 'text': 'QA'})
        self.assertEqual(response.status_code, 429)

    def test_upload_rate_limit_returns_429(self):
        params = {'peer_id': self.ids[1], 'name': 'qa.txt', 'upload_id': str(uuid.uuid4())}
        headers = {**self.headers(), 'Content-Type': 'text/plain',
                   'X-Content-SHA256': hashlib.sha256(b'x').hexdigest()}
        first = self.client.post('/attachments', headers=headers, params=params, content=b'x')
        self.assertEqual(first.status_code, 200, first.text)
        for _ in range(23):
            response = self.client.post('/attachments', headers=headers,
                params={**params, 'upload_id': str(uuid.uuid4())}, content=b'x')
            self.assertEqual(response.status_code, 200, response.text)
        response = self.client.post('/attachments', headers=headers,
            params={**params, 'upload_id': str(uuid.uuid4())}, content=b'x')
        self.assertEqual(response.status_code, 429)
        replay = self.client.post('/attachments', headers=headers, params=params, content=b'x')
        self.assertEqual(replay.status_code, 200, replay.text)
        self.assertEqual(replay.json(), first.json())


if __name__ == '__main__':
    unittest.main()
