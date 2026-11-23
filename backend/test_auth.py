"""Isolated Google identity tests; no real Google credentials or messages."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import tempfile
import time
import unittest
from unittest.mock import patch, MagicMock
import uuid
from fastapi.testclient import TestClient
import app
import auth

class AuthTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous_db = app.DB
        app.DB = Path(self.tmp.name) / 'auth.sqlite3'
        self.credentials = Path(self.tmp.name) / 'project.json'
        self.credentials.write_text(json.dumps({'project_id': 'qa-project'}))
        self.env = patch.dict(os.environ, {'GOOGLE_APPLICATION_CREDENTIALS': str(self.credentials),
                                           'SALOM_DESKTOP_GOOGLE_CLIENT_ID': 'desktop-client-id'})
        self.env.start()
        self.claims = {'sub': 'google-qa', 'email': 'qa@example.invalid', 'email_verified': True,
                       'auth_time': time.time(), 'firebase': {'sign_in_provider': 'google.com'}}
        self.verifier = patch('auth.id_token.verify_firebase_token', side_effect=lambda *a, **k: dict(self.claims))
        self.verify_mock = self.verifier.start()
        self.google_claims = {'sub': 'desktop-google', 'email': 'desktop@example.invalid', 'email_verified': True, 'name': 'Desktop QA'}
        self.google_verifier = patch('auth.id_token.verify_oauth2_token', side_effect=lambda *a, **k: dict(self.google_claims))
        self.google_verify_mock = self.google_verifier.start()
        self.custom_token = patch('auth.issue_desktop_firebase_token', return_value='firebase-custom-fixture')
        self.custom_token_mock = self.custom_token.start()
        self.push = patch('app.PushDispatcher')
        self.push.start()
        self.client = TestClient(app.app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.push.stop(); self.custom_token.stop(); self.google_verifier.stop(); self.verifier.stop(); self.env.stop()
        app.DB = self.previous_db
        self.tmp.cleanup()

    def login(self, **kwargs):
        return self.client.post('/auth/identity', json={'firebase_token': 'verified-google-fixture', **kwargs})

    def register(self, username='qauser'):
        result = self.login(username=username, name='QA')
        self.assertEqual(result.status_code, 200, result.text)
        return result.json()

    def headers(self, result): return {'Authorization': 'Bearer ' + result['session_token']}

    def seed_legacy(self, username='legacy_qa'):
        profile_id, token = str(uuid.uuid4()), secrets.token_urlsafe(32)
        with app.connect() as db:
            db.execute('INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)', (profile_id, auth.hash_token(token), 'Legacy QA', username, username or None, time.time(), time.time()))
        return profile_id, token

    def test_only_google_routes_are_available(self):
        config = self.client.get('/auth/config').json()
        self.assertTrue(config['google_enabled'])
        self.assertFalse(config['sms_enabled']); self.assertFalse(config['email_enabled'])
        for route in ('email/start', 'email/confirm', 'login', 'password'):
            self.assertEqual(self.client.post('/auth/' + route, json={}).status_code, 404)
        self.assertEqual(self.client.post('/auth/identity', json={'email_ticket': 'secret'}).status_code, 422)

    def test_unverified_enrollment_is_closed(self):
        response = self.client.put('/profile', headers={'Authorization': 'Bearer ' + secrets.token_urlsafe(32)},
            json={'device_id': str(uuid.uuid4()), 'name': 'Bypass', 'username': 'bypass_qa'})
        self.assertEqual(response.status_code, 426)
        self.assertEqual(self.client.post('/auth/identity', json={}).status_code, 422)

    def test_google_proof_provider_verified_email_and_freshness(self):
        good = dict(self.claims)
        for change in ({'firebase': {'sign_in_provider': 'phone'}}, {'email_verified': False},
                       {'auth_time': time.time()-901}, {'auth_time': time.time()+60}, {'sub': ''}, {'email': 'bad'}):
            self.claims = {**good, **change}
            self.assertEqual(self.login().status_code, 401, change)
        self.claims = good
        self.assertTrue(self.login().json()['needs_username'])
        self.assertEqual(self.verify_mock.call_args.kwargs['audience'], 'qa-project')

    def test_invalid_signature_is_rejected(self):
        self.verify_mock.side_effect = ValueError('invalid signature')
        self.assertEqual(self.login().status_code, 401)

    def test_desktop_google_proof_is_verified_for_existing_client_and_bridged(self):
        response = self.client.post('/auth/desktop/custom-token', json={'google_id_token': 'signed-google-id-token'})
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), {'firebase_custom_token': 'firebase-custom-fixture'})
        self.assertEqual(self.google_verify_mock.call_args.kwargs['audience'], 'desktop-client-id')
        self.custom_token_mock.assert_called_once_with(self.google_claims)

    def test_desktop_bridge_rejects_unverified_email_or_invalid_google_signature(self):
        self.google_claims['email_verified'] = False
        self.assertEqual(self.client.post('/auth/desktop/custom-token', json={'google_id_token': 'token'}).status_code, 401)
        self.google_claims['email_verified'] = True
        self.google_verify_mock.side_effect = ValueError('bad signature')
        self.assertEqual(self.client.post('/auth/desktop/custom-token', json={'google_id_token': 'token'}).status_code, 401)

    def test_firebase_custom_identity_requires_server_google_claim(self):
        self.claims['firebase'] = {'sign_in_provider': 'custom'}
        self.assertEqual(self.login(username='custom_qa', name='QA').status_code, 401)
        self.claims['salom_google_verified'] = True
        self.assertEqual(self.login(username='custom_qa', name='QA').status_code, 200)

    def test_username_required_then_relogin_keeps_identity(self):
        self.assertTrue(self.login().json()['needs_username'])
        with app.connect() as db: self.assertEqual(db.execute('SELECT count(*) FROM profiles').fetchone()[0], 0)
        account = self.register()
        again = self.login().json()
        self.assertEqual(again['profile']['id'], account['profile']['id'])
        self.assertNotEqual(again['session_token'], account['session_token'])
        self.assertTrue(again['google_linked'])
        self.assertEqual(set(again['profile']), {'id', 'name', 'username', 'is_active', 'avatar_url'})
        self.assertNotIn('email', self.client.get('/search?q=qa', headers=self.headers(account)).text)

    def test_username_conflict_is_case_insensitive_and_retryable(self):
        self.register('occupied_qa')
        self.claims.update(sub='second', email='second@example.invalid')
        self.assertEqual(self.login(username='OCCUPIED_QA', name='Second').status_code, 409)
        self.assertEqual(self.login(username='available_qa', name='Second').status_code, 200)

    def test_binding_preserves_profile_and_revokes_legacy_key(self):
        profile, key = self.seed_legacy()
        response = self.client.post('/auth/identity', json={'firebase_token': 'proof', 'bind_current': True}, headers={'Authorization': 'Bearer ' + key})
        self.assertEqual(response.status_code, 200, response.text)
        account = response.json()
        self.assertEqual(account['profile']['id'], profile)
        self.assertEqual(account['profile']['username'], 'legacy_qa')
        self.assertEqual(self.client.get('/auth/me', headers={'Authorization': 'Bearer ' + key}).status_code, 401)
        # A lost response can be recovered with the same Google identity.
        retry = self.client.post('/auth/identity', json={'firebase_token': 'proof', 'bind_current': True}, headers={'Authorization': 'Bearer ' + key})
        self.assertEqual(retry.json()['profile']['id'], profile)
        self.assertEqual(self.client.get('/auth/me', headers=self.headers(account)).json()['email'], 'qa@example.invalid')

    def test_existing_email_owner_must_bind_with_current_session(self):
        profile, key = self.seed_legacy()
        with app.connect() as db:
            db.execute('INSERT INTO accounts(profile_id,email,created) VALUES(?,?,?)', (profile, self.claims['email'], time.time()))
        self.assertEqual(self.login().status_code, 409)
        result = self.client.post('/auth/identity', json={'firebase_token': 'proof', 'bind_current': True}, headers={'Authorization': 'Bearer ' + key})
        self.assertEqual(result.status_code, 200, result.text)
        self.assertEqual(result.json()['profile']['id'], profile)

    def test_binding_keeps_existing_conversation(self):
        profile, key = self.seed_legacy()
        peer, _ = self.seed_legacy('peer_qa')
        with app.connect() as db:
            db.execute('INSERT INTO messages(sender_id,recipient_id,client_id,text,created) VALUES(?,?,?,?,?)', (peer, profile, str(uuid.uuid4()), 'Existing history', time.time()))
        account = self.client.post('/auth/identity', json={'firebase_token': 'proof', 'bind_current': True}, headers={'Authorization': 'Bearer ' + key}).json()
        history = self.client.get('/messages?peer_id='+peer, headers=self.headers(account))
        self.assertEqual(history.status_code, 200)
        self.assertEqual(history.json()['messages'][0]['text'], 'Existing history')

    def test_expired_session_cannot_access_profile(self):
        account = self.register()
        with app.connect() as db: db.execute('UPDATE sessions SET expires=?', (time.time()-1,))
        self.assertEqual(self.client.get('/auth/me', headers=self.headers(account)).status_code, 401)

    def test_other_google_account_cannot_take_over_profile(self):
        profile, key = self.seed_legacy()
        account = self.client.post('/auth/identity', json={'firebase_token': 'proof', 'bind_current': True}, headers={'Authorization': 'Bearer ' + key}).json()
        self.claims.update(sub='second', email='other@example.invalid')
        self.assertEqual(self.client.post('/auth/identity', json={'firebase_token': 'proof', 'bind_current': True}, headers=self.headers(account)).status_code, 409)
        self.assertEqual(self.client.post('/auth/identity', json={'firebase_token': 'proof', 'bind_current': True}, headers={'Authorization': 'Bearer ' + key}).status_code, 401)

    def test_google_cannot_merge_another_uid_by_email(self):
        self.register()
        self.claims.update(sub='impostor')
        self.assertEqual(self.login().status_code, 409)

    def test_same_uid_can_refresh_google_email(self):
        original = self.register()
        self.claims['email'] = 'renamed@example.invalid'
        result = self.login().json()
        self.assertEqual(result['profile']['id'], original['profile']['id'])
        self.assertEqual(result['email'], 'renamed@example.invalid')

    def test_inactive_profile_cannot_login(self):
        result = self.register()
        with app.connect() as db: db.execute('UPDATE profiles SET is_active=0 WHERE id=?', (result['profile']['id'],))
        self.assertEqual(self.login().status_code, 403)

    def test_logout_revokes_only_current_session(self):
        account = self.register()
        second = self.login().json()
        headers = self.headers(account)
        self.assertEqual(self.client.post('/auth/logout', headers=headers).status_code, 200)
        self.assertEqual(self.client.get('/auth/me', headers=headers).status_code, 401)
        self.assertEqual(self.client.get('/auth/me', headers=self.headers(second)).status_code, 200)
        with app.connect() as db:
            self.assertIsNone(db.execute('SELECT 1 FROM sessions WHERE token_hash=?', (account['session_token'],)).fetchone())

    def test_devices_list_shows_only_my_active_sessions_without_credentials(self):
        account = self.register()
        second = self.client.post('/auth/identity', headers={'X-Salom-Device': 'Windows'},
            json={'firebase_token': 'verified-google-fixture'}).json()
        self.claims.update(sub='other-qa', email='other@example.invalid')
        other = self.register('otherqa')
        headers = {**self.headers(account), 'X-Salom-Device': 'Android / Itel QA'}
        response = self.client.get('/auth/devices', headers=headers)
        self.assertEqual(response.status_code, 200)
        devices = response.json()['devices']
        self.assertEqual({(row['label'], row['current']) for row in devices},
                         {('Android · Itel QA', True), ('Windows', False)})
        self.assertTrue(all(set(row) == {'label', 'current', 'created'} for row in devices))
        self.assertEqual(len(self.client.get('/auth/devices', headers=self.headers(other)).json()['devices']), 1)
        self.assertEqual(self.client.get('/auth/devices').status_code, 401)
        self.assertEqual(self.client.post('/auth/logout', headers=self.headers(second)).status_code, 200)
        self.assertEqual(len(self.client.get('/auth/devices', headers=headers).json()['devices']), 1)

    def test_email_code_is_hashed_and_has_five_minute_ttl(self):
        redis=MagicMock(); redis.set.return_value=True
        with patch.object(app.app.state,'redis_sync',redis,create=True), patch.dict(os.environ,{'BREVO_API_KEY':'fixture','BREVO_SENDER_EMAIL':'sender@example.invalid','SALOM_OTP_PEPPER':'x'*32}), patch('auth.requests.post') as mail:
            response=self.client.post('/auth/email/code',json={'email':'qa@example.invalid'})
            self.assertEqual(response.status_code,200,response.text)
            challenge=json.loads(redis.set.call_args_list[1].args[1])
            sent_code=mail.call_args.kwargs['json']['textContent'].split(': ')[1].split('.')[0]
            self.assertEqual(challenge['digest'],auth.otp_digest('qa@example.invalid',sent_code))
            self.assertNotIn(sent_code,redis.set.call_args_list[1].args[1])
            self.assertEqual(redis.set.call_args_list[1].kwargs['ex'],300)
            self.assertEqual(mail.call_args.args[0],'https://api.brevo.com/v3/smtp/email')

    def test_email_resend_is_limited(self):
        redis=MagicMock();redis.set.return_value=False
        with patch.object(app.app.state,'redis_sync',redis,create=True), patch.dict(os.environ,{'BREVO_API_KEY':'fixture','BREVO_SENDER_EMAIL':'sender@example.invalid'}), patch('auth.requests.post') as mail:
            self.assertEqual(self.client.post('/auth/email/code',json={'email':'qa@example.invalid'}).status_code,429)
            mail.assert_not_called()

    def test_email_verification_limits_expiry_and_reuses_google_account(self):
        account=self.register()
        redis=MagicMock()
        with patch.object(app.app.state,'redis_sync',redis,create=True), patch.dict(os.environ,{'SALOM_OTP_PEPPER':'x'*32}):
            for code,status in [(-1,400),(-2,429),(0,400)]:
                redis.eval.return_value=code
                self.assertEqual(self.client.post('/auth/email/verify',json={'email':'qa@example.invalid','code':'123456'}).status_code,status)
            redis.eval.return_value=1
            response=self.client.post('/auth/email/verify',json={'email':'qa@example.invalid','code':'123456'})
            self.assertEqual(response.json()['profile']['id'],account['profile']['id'])
            self.assertEqual(self.client.get('/auth/me',headers=self.headers(response.json())).status_code,200)
            with app.connect() as db: self.assertEqual(db.execute('SELECT count(*) FROM profiles').fetchone()[0],1)

    def test_google_links_to_verified_email_registration_without_new_profile(self):
        profile,key=self.seed_legacy('email_user')
        with app.connect() as db:
            db.execute('INSERT INTO accounts(profile_id,email,created) VALUES(?,?,?)',(profile,self.claims['email'],time.time()))
            db.execute('INSERT INTO email_identities(profile_id) VALUES(?)',(profile,))
        result=self.login()
        self.assertEqual(result.status_code,200,result.text)
        self.assertEqual(result.json()['profile']['id'],profile)
        with app.connect() as db: self.assertEqual(db.execute('SELECT count(*) FROM profiles').fetchone()[0],1)

    def test_email_registration_ticket_is_single_use(self):
        redis=MagicMock();redis.get.return_value='new@example.invalid';redis.getdel.side_effect=['new@example.invalid',None]
        with patch.object(app.app.state,'redis_sync',redis,create=True):
            body={'email':'new@example.invalid','ticket':'t'*32,'name':'Email QA','username':'email_qa'}
            first=self.client.post('/auth/email/register',json=body)
            self.assertEqual(first.status_code,200,first.text)
            self.assertEqual(self.client.post('/auth/email/register',json=body).status_code,400)
            with app.connect() as db: self.assertEqual(db.execute('SELECT count(*) FROM profiles').fetchone()[0],1)

if __name__ == '__main__': unittest.main()
