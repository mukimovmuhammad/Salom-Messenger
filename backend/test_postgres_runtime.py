"""PostgreSQL API smoke test against the disposable migration rehearsal database."""
import hashlib
from concurrent.futures import ThreadPoolExecutor
import os
import secrets
import tempfile
import time
from threading import Barrier
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import psycopg
from starlette.testclient import TestClient

import app
import media


@unittest.skipUnless(os.environ.get("SALOM_PG_TEST_DSN"), "requires a disposable PostgreSQL *_test database")
class PostgresRuntimeTests(unittest.TestCase):
    def test_send_read_edit_and_delete_routes(self):
        dsn = os.environ["SALOM_PG_TEST_DSN"]
        alice_id, bob_id, stranger_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
        alice_token, bob_token, stranger_token = (secrets.token_urlsafe(36) for _ in range(3))
        suffix = uuid.uuid4().hex[:8]
        now = time.time()
        with psycopg.connect(dsn) as db:
            for profile_id, token, username in (
                (alice_id, alice_token, "pgrtalice" + suffix),
                (bob_id, bob_token, "pgrtbob" + suffix),
                (stranger_id, stranger_token, "pgrtstranger" + suffix),
            ):
                db.execute(
                    "INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(%s,%s,%s,%s,%s,1,%s,%s)",
                    (profile_id, hashlib.sha256(token.encode()).hexdigest(), username, username, username, now, now)
                )

        try:
            with tempfile.TemporaryDirectory() as media_root, \
                    patch.object(media, "ROOT", Path(media_root)), \
                    patch.object(app, "PG_DSN", dsn), TestClient(app.app) as client:
                client_id = str(uuid.uuid4())
                body = {"recipient_id": bob_id, "client_id": client_id, "text": "synthetic PostgreSQL smoke"}
                send_gate = Barrier(2)
                def submit(_):
                    send_gate.wait(timeout=5)
                    return client.post("/messages", headers={"Authorization": "Bearer " + alice_token}, json=body)
                with ThreadPoolExecutor(max_workers=2) as pool:
                    sent, replay = list(pool.map(submit, range(2)))
                self.assertEqual(sent.status_code, 200, sent.text)
                self.assertEqual(replay.status_code, 200, replay.text)
                message_id = sent.json()["id"]
                self.assertEqual(replay.json()["id"], message_id)
                self.assertEqual(replay.json()["client_id"], client_id)
                with psycopg.connect(dsn) as db:
                    self.assertEqual(db.execute("SELECT count(*) FROM messages WHERE sender_id=%s AND client_id=%s",
                                                (alice_id, client_id)).fetchone()[0], 1)
                bob_auth = {"Authorization": "Bearer " + bob_token}
                for _ in range(12):
                    with psycopg.connect(dsn) as db:
                        db.execute(
                            "INSERT INTO messages(sender_id,recipient_id,client_id,text,created) VALUES(%s,%s,%s,%s,%s)",
                            (alice_id, bob_id, str(uuid.uuid4()), "receipt concurrency probe", now),
                        )
                start = Barrier(4)
                def fetch(index):
                    start.wait(timeout=5)
                    path = "/messages" if index % 2 else "/conversations"
                    return client.get(path, params={"peer_id": alice_id}, headers=bob_auth).status_code
                with ThreadPoolExecutor(max_workers=4) as pool:
                    self.assertEqual(list(pool.map(fetch, range(4))), [200] * 4)
                with psycopg.connect(dsn) as db:
                    pending = db.execute(
                        "SELECT count(*) FROM messages WHERE recipient_id=%s AND (read_at IS NULL OR delivered_at IS NULL)",
                        (bob_id,),
                    ).fetchone()[0]
                    self.assertEqual(pending, 0)
                found = client.get("/search", params={"q": "pgrtbob" + suffix}, headers={"Authorization": "Bearer " + alice_token})
                self.assertEqual(found.status_code, 200, found.text)
                self.assertIn(bob_id, {profile["id"] for profile in found.json()["profiles"]})
                received = client.get("/messages", params={"peer_id": alice_id}, headers={"Authorization": "Bearer " + bob_token})
                self.assertEqual(received.status_code, 200, received.text)
                self.assertEqual(received.json()["messages"][0]["id"], message_id)
                inbox = client.get("/conversations", headers={"Authorization": "Bearer " + bob_token})
                self.assertEqual(inbox.status_code, 200, inbox.text)
                self.assertEqual(inbox.json()["conversations"][0]["profile"]["id"], alice_id)
                edited = client.put(f"/messages/{message_id}", headers={"Authorization": "Bearer " + alice_token}, json={"text": "edited PostgreSQL smoke"})
                self.assertEqual(edited.status_code, 200, edited.text)
                self.assertTrue(edited.json()["edited_at"])
                removed = client.delete(f"/messages/{message_id}?scope=everyone", headers={"Authorization": "Bearer " + alice_token})
                self.assertEqual(removed.status_code, 200, removed.text)
                typing = client.post("/typing", headers={"Authorization": "Bearer " + alice_token}, json={"peer_id": bob_id, "typing": True})
                self.assertEqual(typing.status_code, 200, typing.text)

                uploaded = client.post(
                    "/attachments", headers={"Authorization": "Bearer " + alice_token, "Content-Type": "text/plain"},
                    params={"peer_id": bob_id, "name": "synthetic.txt"}, content=b"postgres attachment smoke",
                )
                self.assertEqual(uploaded.status_code, 200, uploaded.text)
                attachment_id = uploaded.json()["id"]
                self.assertEqual(client.get(f"/attachments/{attachment_id}", headers={"Authorization": "Bearer " + stranger_token}).status_code, 404)
                attached = client.post("/messages", headers={"Authorization": "Bearer " + alice_token}, json={
                    "recipient_id": bob_id, "client_id": str(uuid.uuid4()), "text": "attachment", "attachment_id": attachment_id,
                })
                self.assertEqual(attached.status_code, 200, attached.text)
                downloaded = client.get(f"/attachments/{attachment_id}", headers={"Authorization": "Bearer " + bob_token})
                self.assertEqual(downloaded.status_code, 200, downloaded.text)
                self.assertEqual(downloaded.content, b"postgres attachment smoke")

                once = client.post("/locations/once", headers={"Authorization": "Bearer " + alice_token}, json={
                    "peer_id": bob_id, "latitude": 38.56, "longitude": 68.78,
                })
                self.assertEqual(once.status_code, 200, once.text)
                live = client.post("/locations", headers={"Authorization": "Bearer " + alice_token}, json={
                    "peer_id": bob_id, "duration_minutes": 15,
                })
                self.assertEqual(live.status_code, 200, live.text)
                share_id = live.json()["id"]
                self.assertEqual(client.get(f"/locations/{share_id}", headers={"Authorization": "Bearer " + bob_token}).status_code, 200)
                self.assertEqual(client.get(f"/locations/{share_id}", headers={"Authorization": "Bearer " + stranger_token}).status_code, 404)
                self.assertEqual(client.put(f"/locations/{share_id}", headers={"Authorization": "Bearer " + alice_token}, json={
                    "latitude": 38.57, "longitude": 68.79,
                }).status_code, 200)
                self.assertEqual(client.delete(f"/locations/{share_id}", headers={"Authorization": "Bearer " + alice_token}).status_code, 200)
                cleared = client.delete("/messages", params={"peer_id": bob_id}, headers={"Authorization": "Bearer " + alice_token})
                self.assertEqual(cleared.status_code, 200, cleared.text)
        finally:
            app.close_pg_pool()


if __name__ == "__main__":
    unittest.main()
