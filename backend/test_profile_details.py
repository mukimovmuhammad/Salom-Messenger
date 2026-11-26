import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient

import app


class ProfileDetailsTests(unittest.TestCase):
    def test_about_and_joined_date_without_exposing_account_email(self):
        temporary = self.enterContext(tempfile.TemporaryDirectory())
        self.enterContext(patch.multiple(app, DB=Path(temporary) / 'profiles.sqlite3', PG_DSN=None, redis_sync=None))
        app.initialize()
        owner, peer = str(uuid4()), str(uuid4())
        owner_token, peer_token = 'A' * 48, 'B' * 48
        joined_at = 1_750_000_000
        with app.connect() as db:
            for profile, token, user in ((owner, owner_token, 'owner'), (peer, peer_token, 'peer')):
                db.execute('INSERT INTO profiles(id,token_hash,name,username,username_key,created,updated) VALUES(?,?,?,?,?,?,?)',
                           (profile, hashlib.sha256(token.encode()).hexdigest(), user, user, user, joined_at, joined_at))
            db.execute('INSERT INTO accounts(profile_id,email,created) VALUES(?,?,?)', (owner, 'private@example.test', joined_at))
        client = TestClient(app.app)
        own = {'Authorization': 'Bearer ' + owner_token}
        other = {'Authorization': 'Bearer ' + peer_token}
        edit = {'device_id': owner, 'name': 'Owner', 'username': 'owner'}
        self.assertEqual(client.put('/profile', json={**edit, 'about': 'No'}, headers=other).status_code, 403)
        self.assertEqual(client.put('/profile', json={**edit, 'about': 'x' * 161}, headers=own).status_code, 422)
        self.assertEqual(client.put('/profile', json={**edit, 'about': '  Салом!  '}, headers=own).json()['about'], 'Салом!')
        self.assertEqual(client.put('/profile', json=edit, headers=own).json()['about'], 'Салом!')

        self.assertEqual(client.get(f'/profiles/{owner}').status_code, 401)
        public = client.get(f'/profiles/{owner}', headers=other).json()
        self.assertEqual((public['about'], public['joined_at']), ('Салом!', joined_at))
        self.assertNotIn('email', public)
        self.assertNotIn('token_hash', public)
        self.assertEqual(client.put('/profile', json={**edit, 'about': ''}, headers=own).json()['about'], '')
        self.assertEqual(client.get(f'/profiles/{owner}', headers=other).json()['about'], '')
