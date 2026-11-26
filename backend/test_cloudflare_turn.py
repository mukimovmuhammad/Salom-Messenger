import json
import unittest
from unittest.mock import patch

import app
from fastapi import HTTPException

from cloudflare_turn import issue_turn_servers


TLS_URL = 'turns:turn.cloudflare.com:443?transport=tcp'
RELAY = {'urls': [TLS_URL], 'username': 'short-lived-user', 'credential': 'short-lived-pass'}


class CloudflareTurnTests(unittest.TestCase):
    @patch('cloudflare_turn.http.client.HTTPSConnection')
    def test_issues_temporary_credentials_and_filters_urls(self, https):
        connection = https.return_value
        response = connection.getresponse.return_value
        response.status = 201
        response.read.return_value = json.dumps({'iceServers': [
            {'urls': ['stun:stun.cloudflare.com:3478']},
            dict(RELAY, urls=[TLS_URL, 'turn:turn.cloudflare.com:53?transport=udp', TLS_URL]),
        ]}).encode()
        servers = issue_turn_servers('test-key', 'test-token', 14700)
        self.assertEqual(servers, [RELAY])
        https.assert_called_once_with('rtc.live.cloudflare.com', timeout=3)
        method, path = connection.request.call_args.args
        self.assertEqual((method, path), ('POST', '/v1/turn/keys/test-key/credentials/generate-ice-servers'))
        self.assertEqual(json.loads(connection.request.call_args.kwargs['body']), {'ttl': 14700})
        self.assertEqual(connection.request.call_args.kwargs['headers']['Authorization'], 'Bearer test-token')
        connection.close.assert_called_once()

    @patch('cloudflare_turn.http.client.HTTPSConnection')
    def test_provider_failure_does_not_expose_error_body(self, https):
        response = https.return_value.getresponse.return_value
        response.status = 401
        with self.assertLogs('cloudflare_turn', level='WARNING') as logs:
            self.assertEqual(issue_turn_servers('test-key', 'test-token', 14700), [])
        response.read.assert_not_called()
        self.assertNotIn('test-token', '\n'.join(logs.output))

    @patch.dict('os.environ', {
        'SALOM_CLOUDFLARE_TURN_KEY_ID': 'test-key',
        'SALOM_CLOUDFLARE_TURN_TOKEN': 'test-token',
        'SALOM_TURN_URLS': 'turn:relay.example:3478?transport=udp',
        'SALOM_TURN_SECRET': 'test-shared-secret',
    }, clear=True)
    @patch('app.issue_turn_servers')
    @patch('app.throttle')
    @patch('app.current_profile')
    def test_route_authorizes_and_throttles_before_issuing_or_falling_back(self, profile, throttle, issue):
        profile.side_effect = HTTPException(401)
        with self.assertRaises(HTTPException):
            app.call_config(None)
        throttle.assert_not_called()
        issue.assert_not_called()

        profile.side_effect = None
        profile.return_value = {'id': 'profile'}
        issue.return_value = [RELAY]
        self.assertEqual(app.call_config('Bearer test')['ice_servers'][1:], [RELAY])
        throttle.assert_any_call(('cloudflare-turn', 'profile'), limit=12)
        throttle.assert_any_call('cloudflare-turn-global', limit=120)
        issue.assert_called_once_with('test-key', 'test-token', app.CALL_MAX_DURATION_SECONDS + 300)

        issue.return_value = []
        result = app.call_config('Bearer test')['ice_servers']
        self.assertEqual(result[1]['urls'], ['turn:relay.example:3478?transport=udp'])
