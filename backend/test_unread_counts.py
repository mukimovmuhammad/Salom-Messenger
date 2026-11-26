"""Unread badges come from durable read state, not WebSocket delivery counts."""
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
import app


class UnreadCountsTest(unittest.TestCase):
    def test_repeated_list_does_not_increment_and_read_syncs(self):
        with tempfile.TemporaryDirectory() as folder:
            previous = app.DB
            app.DB = Path(folder) / 'salom.sqlite3'
            try:
                with patch('app.PushDispatcher'):
                    with TestClient(app.app) as client:
                        now = time.time()
                        with app.connect() as db:
                            for profile in ('sender', 'reader'):
                                db.execute('INSERT INTO profiles(id,token_hash,name,created,updated) VALUES(?,?,?,?,?)',
                                           (profile, profile + '-token', profile, now, now))
                            for index in range(5):
                                db.execute('INSERT INTO messages(sender_id,recipient_id,client_id,text,created) VALUES(?,?,?,?,?)',
                                           ('sender', 'reader', str(index), 'hello', now + index))
                        with patch('app.current_profile', return_value={'id': 'reader'}):
                            for _ in range(3):
                                result = client.get('/conversations')
                                self.assertEqual(result.status_code, 200, result.text)
                                self.assertEqual(result.json()['conversations'][0]['unread_count'], 5)
                            # Notification actions acknowledge the chat without loading its history.
                            cursor = (1 << 63) - 1
                            read = client.get('/messages', params={
                                'peer_id': 'sender', 'after': cursor, 'receipts_after': cursor, 'events_after': cursor,
                            })
                            self.assertEqual(read.status_code, 200, read.text)
                            self.assertEqual(read.json()['messages'], [])
                            self.assertEqual(client.get('/conversations').json()['conversations'][0]['unread_count'], 0)
            finally:
                app.DB = previous


if __name__ == '__main__':
    unittest.main()
