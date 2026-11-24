"""Synthetic SQLite-to-PostgreSQL rehearsal. Set SALOM_PG_TEST_DSN to an empty *_test DB."""
import os
from pathlib import Path
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

import app
import psycopg
from migrate_sqlite_to_postgres import TABLES, migrate


@unittest.skipUnless(os.environ.get("SALOM_PG_TEST_DSN"), "requires a disposable PostgreSQL test database")
class PostgresMigrationRehearsalTests(unittest.TestCase):
    def test_synthetic_rows_migrate_with_counts_checksums_and_primary_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source-backup.sqlite3"
            with patch.object(app, "DB", source):
                app.initialize()
                a, b, file_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
                now, client_id = time.time(), str(uuid.uuid4())
                with app.connect() as db:
                    db.execute("INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)", (a, "a" * 64, "Synthetic A", "syntha", "syntha", now, now))
                    db.execute("INSERT INTO profiles(id,token_hash,name,username,username_key,is_active,created,updated) VALUES(?,?,?,?,?,1,?,?)", (b, "b" * 64, "Synthetic B", "synthb", "synthb", now, now))
                    db.execute("INSERT INTO registrations(ip,at) VALUES(?,?)", ("192.0.2.10", now))
                    db.execute("INSERT INTO attachments(id,owner_id,peer_id,name,mime,size,created,ready,kind,duration_ms,waveform) VALUES(?,?,?,?,?,?,?,1,?,?,?)", (file_id, a, b, "test.png", "image/png", 123, now, "photo", 0, "[]"))
                    db.execute("INSERT INTO conversation_clears(owner_id,peer_id,through_id) VALUES(?,?,?)", (a, b, 2))
                    db.execute("INSERT INTO messages(id,sender_id,recipient_id,client_id,text,created,delivered_at,read_at,reply_to_id,attachment_id,edited_at,location_id) VALUES(41,?,?,?,?,?,?,?,?,?,?,?)", (a, b, client_id, "synthetic migration row", now, now, None, None, file_id, None, None))
                    db.execute("INSERT INTO message_events(id,message_id,sender_id,recipient_id,owner_id,kind) VALUES(75,?,?,?,?,?)", (41, a, b, None, "edit"))
                    db.execute("INSERT INTO message_hides(owner_id,message_id) VALUES(?,?)", (b, 41))
                    db.execute("INSERT INTO live_locations(id,owner_id,peer_id,created,expires,latitude,longitude,updated,active) VALUES(?,?,?,?,?,?,?,?,1)", (str(uuid.uuid4()), a, b, now, now + 3600, 38.56, 68.78, now))
                    db.execute("INSERT INTO accounts(profile_id,firebase_uid,email,created,google_linked) VALUES(?,?,?,?,1)", (a, "synthetic-firebase-uid", "migration@example.invalid", now))
                    db.execute("INSERT INTO sessions(token_hash,profile_id,created,expires,verified_at) VALUES(?,?,?,?,?)", ("c" * 64, a, now, now + 3600, now))
                    db.execute("INSERT INTO session_devices(token_hash,label) VALUES(?,?)", ("c" * 64, "Fixture device"))
                    db.execute("INSERT INTO message_reactions(message_id,actor_id,emoji) VALUES(?,?,?)", (41, a, "👍"))
                    db.execute("INSERT INTO auth_limits(key,at) VALUES(?,?)", ("synthetic-limit", now))
                    db.execute("INSERT INTO push_tokens(profile_id,token,updated) VALUES(?,?,?)", (b, "synthetic-fcm-token", now))
                    db.execute("INSERT INTO push_jobs(message_id,state,attempts,next_at,last_error) VALUES(41,'sent',1,0,NULL)")

            report = migrate(source, os.environ["SALOM_PG_TEST_DSN"])
            self.assertEqual(set(report), set(TABLES))
            self.assertEqual(report["profiles"]["rows"], 2)
            self.assertEqual(report["messages"]["rows"], 1)
            self.assertEqual(report["message_reactions"]["rows"], 1)
            self.assertEqual(report["session_devices"]["rows"], 1)
            with psycopg.connect(os.environ["SALOM_PG_TEST_DSN"]) as pg:
                next_id = pg.execute(
                    "INSERT INTO messages(sender_id,recipient_id,client_id,text,created) VALUES(%s,%s,%s,%s,%s) RETURNING id",
                    (a, b, str(uuid.uuid4()), "sequence check", now)
                ).fetchone()[0]
                next_event_id = pg.execute(
                    "INSERT INTO message_events(message_id,sender_id,recipient_id,kind) VALUES(41,%s,%s,'sync') RETURNING id",
                    (a, b)
                ).fetchone()[0]
            self.assertEqual(next_id, 42)
            self.assertEqual(next_event_id, 76)


if __name__ == "__main__":
    unittest.main()
