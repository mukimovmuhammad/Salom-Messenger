"""Authenticated WebSocket endpoint integration using a disposable SQLite database."""
import hashlib
import secrets
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

import app


class RealtimeEndpointTests(unittest.TestCase):
    def test_http_send_delivers_one_realtime_message_with_the_same_client_id(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(app, "DB", Path(directory) / "message.sqlite3"), patch('app.PushDispatcher'):
            with TestClient(app.app) as client:
                alice, bob = [str(uuid.uuid4()) for _ in range(2)]
                alice_token, bob_token = [secrets.token_urlsafe(36) for _ in range(2)]
                with app.connect() as db:
                    for index, (profile_id, token) in enumerate(((alice, alice_token), (bob, bob_token))):
                        now = time.time()
                        db.execute("INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)",
                                   (profile_id, hashlib.sha256(token.encode()).hexdigest(), "WS QA", f"sendqa{index}", f"sendqa{index}", now, now))
                sender = {"Authorization": "Bearer " + alice_token}
                recipient = {"Authorization": "Bearer " + bob_token}
                with client.websocket_connect("/ws", headers=recipient) as socket:
                    self.assertEqual(socket.receive_json()["type"], "ready")
                    socket.send_json({"type": "subscribe", "peer_id": alice})
                    self.assertEqual(socket.receive_json()["type"], "subscribed")
                    self.assertEqual(socket.receive_json()["type"], "presence")
                    logical_id = str(uuid.uuid4())
                    body = {"recipient_id": bob, "client_id": logical_id, "text": "synthetic QA message"}
                    response = client.post("/messages", headers=sender, json=body)
                    self.assertEqual(response.status_code, 200)
                    committed = response.json()
                    event = socket.receive_json()
                    self.assertEqual(event["type"], "message")
                    self.assertEqual((event["message"]["client_id"], event["message"]["id"]), (logical_id, committed["id"]))
                    throttle = app.throttle
                    def throttle_with_full_send_quota(key, limit=180):
                        if key == 'send-message:' + alice:
                            raise app.HTTPException(429, 'Quota reached')
                        throttle(key, limit)
                    with patch('app.throttle', side_effect=throttle_with_full_send_quota):
                        retry = client.post("/messages", headers=sender, json=body)
                        new_message = client.post("/messages", headers=sender, json=dict(body, client_id=str(uuid.uuid4())))
                    self.assertEqual(retry.status_code, 200)
                    self.assertEqual(retry.json()["id"], committed["id"])
                    self.assertEqual(new_message.status_code, 429)
                    # A later marker proves the retry did not enqueue a second message event.
                    marker = dict(body, client_id=str(uuid.uuid4()))
                    self.assertEqual(client.post("/messages", headers=sender, json=marker).status_code, 200)
                    self.assertEqual(socket.receive_json()["message"]["client_id"], marker["client_id"])
                    reacted = client.put(f'/messages/{committed["id"]}/reaction', headers=sender, json={'emoji': '👍'})
                    self.assertEqual(reacted.status_code, 200)
                    self.assertEqual(socket.receive_json(), {'type': 'sync', 'peer_id': alice})
                    history = client.get(f'/messages?peer_id={alice}&after={committed["id"]}', headers=recipient).json()
                    reaction = next(event for event in history['events'] if event['kind'] == 'reaction')
                    self.assertEqual(reaction['message']['reactions'], {'sender': '👍', 'recipient': None})
                    cursor = reaction['id']
                route = f'/messages/{committed["id"]}/reaction'
                for emoji in ('🔥', '🔥', None):
                    self.assertEqual(client.put(route, headers=sender, json={'emoji': emoji}).status_code, 200)
                with client.websocket_connect("/ws", headers=recipient) as reconnected:
                    self.assertEqual(reconnected.receive_json()["type"], "ready")
                    reconnected.send_json({"type": "subscribe", "peer_id": alice})
                    self.assertEqual(reconnected.receive_json()["type"], "subscribed")
                    self.assertEqual(reconnected.receive_json()["type"], "presence")
                    history = client.get('/messages', params={'peer_id': alice, 'after': committed['id'],
                        'events_after': cursor}, headers=recipient).json()
                    events = history['events']
                    self.assertEqual([event['kind'] for event in events], ['reaction', 'reaction'])
                    self.assertEqual(events[-1]['message']['reactions'], {'sender': None, 'recipient': None})
                    self.assertEqual([message['client_id'] for message in history['messages']], [marker['client_id']])

    def test_bearer_auth_subscription_and_typing_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(app, "DB", Path(directory) / "realtime.sqlite3"):
                app.initialize()
                accounts = [(str(uuid.uuid4()), "A" * 43), (str(uuid.uuid4()), "B" * 43)]
                with app.connect() as db:
                    for index, (profile_id, token) in enumerate(accounts):
                        now = time.time()
                        db.execute("INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)",
                                   (profile_id, hashlib.sha256(token.encode()).hexdigest(), "WS QA", f"wsqa{index}", f"wsqa{index}", now, now))
                alice, bob = accounts
                with TestClient(app.app) as client:
                    with self.assertRaises(WebSocketDisconnect) as denied:
                        client.websocket_connect("/ws").__enter__()
                    self.assertEqual(denied.exception.code, 4401)
                    with client.websocket_connect("/ws", headers={"Authorization": "Bearer " + bob[1]}) as bob_socket:
                        self.assertEqual(bob_socket.receive_json(), {"type": "ready"})
                        bob_socket.send_json({"type": "subscribe", "peer_id": alice[0]})
                        self.assertEqual(bob_socket.receive_json(), {"type": "subscribed", "peer_id": alice[0]})
                        self.assertEqual(bob_socket.receive_json(), {"type": "presence", "peer_id": alice[0], "online": False})
                        with client.websocket_connect("/ws", headers={"Authorization": "Bearer " + alice[1]}) as alice_socket:
                            self.assertEqual(alice_socket.receive_json(), {"type": "ready"})
                            alice_socket.send_json({"type": "subscribe", "peer_id": bob[0]})
                            self.assertEqual(alice_socket.receive_json(), {"type": "subscribed", "peer_id": bob[0]})
                            self.assertEqual(alice_socket.receive_json(), {"type": "presence", "peer_id": bob[0], "online": True})
                            self.assertEqual(bob_socket.receive_json(), {"type": "presence", "peer_id": alice[0], "online": True})
                            alice_socket.send_json({"type": "typing", "peer_id": bob[0], "typing": True})
                            self.assertEqual(bob_socket.receive_json(), {"type": "typing", "peer_id": alice[0], "typing": True})


if __name__ == "__main__":
    unittest.main()
