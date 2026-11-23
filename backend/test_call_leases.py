import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

import app


class CallLeaseTests(unittest.TestCase):
    def setUp(self):
        tmp = self.enterContext(tempfile.TemporaryDirectory())
        self.enterContext(patch.multiple(app, DB=Path(tmp) / 'test.sqlite3', PG_DSN=None))
        self.clock = self.enterContext(patch.object(app.time, 'time', return_value=1000.0))
        self.publish = self.enterContext(patch.object(app, 'publish_realtime'))
        self.profile = self.enterContext(patch.object(app, 'current_profile'))
        self.a, self.b = str(uuid4()), str(uuid4())
        app.initialize()
        with app.connect() as db:
            for profile in (self.a, self.b):
                db.execute('INSERT INTO profiles(id,token_hash,name,created,updated) VALUES(?,?,?,?,?)',
                           (profile, profile, 'Test', 1000, 1000))

    def create(self):
        self.profile.return_value = {'id': self.a, 'name': 'Test'}
        return app.create_call(app.CallCreateInput(peer_id=self.b), None)['call_id']

    def action(self, call, who, action):
        self.profile.return_value = {'id': who}
        return app.update_call(call, app.CallStateInput(action=action), None)['state']

    def test_crash_releases_busy_after_lease(self):
        call = self.create()
        self.assertEqual(self.action(call, self.b, 'accept_leased'), 'accepted')
        with self.assertRaises(app.HTTPException) as exc:
            self.create()
        self.assertEqual(exc.exception.status_code, 409)
        self.clock.return_value = 1061.0
        app.expire_accepted_calls()
        self.assertEqual(self.action(call, self.b, 'heartbeat'), 'ended')
        self.assertNotEqual(self.create(), call)

    def test_heartbeat_extends_lease_and_end_is_idempotent(self):
        call = self.create()
        self.action(call, self.b, 'accept_leased')
        self.clock.return_value = 1030.0
        self.action(call, self.a, 'heartbeat')
        self.clock.return_value = 1070.0
        self.assertEqual(self.action(call, self.b, 'accept_leased'), 'accepted')
        self.assertEqual(self.action(call, self.a, 'end'), 'ended')
        self.assertEqual(self.action(call, self.b, 'end'), 'ended')
        self.assertNotEqual(self.create(), call)

    def test_late_accept_cannot_revive_ringing_call(self):
        call = self.create()
        self.clock.return_value = 1046.0
        self.assertEqual(self.action(call, self.b, 'accept_leased'), 'missed')
        self.assertNotEqual(self.create(), call)

    def test_audio_default_and_explicit_video_mode(self):
        first = self.create()
        self.assertEqual(self.publish.call_args.args[1]['mode'], 'audio')
        self.action(first, self.a, 'cancel')
        self.profile.return_value = {'id': self.a, 'name': 'Test'}
        result = app.create_call(app.CallCreateInput(peer_id=self.b, mode='video'), None)
        self.assertEqual(result['mode'], 'video')
        self.assertEqual(self.publish.call_args.args[1]['mode'], 'video')
        with app.connect() as db:
            self.assertEqual(db.execute('SELECT media_mode FROM call_sessions WHERE id=?',
                                        (result['call_id'],)).fetchone()['media_mode'], 'video')

    def test_concurrent_invites_cannot_create_two_active_calls(self):
        def invite():
            try:
                self.create()
                return 200
            except app.HTTPException as exc:
                return exc.status_code

        with ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(lambda _: invite(), range(2)))
        self.assertCountEqual(results, [200, 409])
