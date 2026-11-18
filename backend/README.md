# Salom directory and messaging API

Purpose: username lookup and authenticated messaging for Android and Windows SALOM clients. SQLite remains available for local development and the legacy VPS; the new VPS uses a clean PostgreSQL database and private Redis instance.

| Endpoint (prefix `/salom/api`) | Authentication | Behavior |
| --- | --- | --- |
| GET `/health` | None | Database readiness |
| PUT `/profile` | Bearer installation key | Idempotent enrollment or own profile update; 409 for occupied username, 403 for another owner's ID |
| GET `/search?q=prefix` | Registered Bearer installation key | Up to 20 active public profiles, case-insensitive username prefix; optional @ |
| POST `/messages` | Registered Bearer installation key | Send to an active recipient; client UUID makes retries idempotent; 4,000 character limit |
| GET `/messages?peer_id=UUID&after=0` | Registered Bearer installation key | Only the caller's conversation with this peer; batches of 100, ascending server IDs |
| GET `/conversations` | Registered Bearer installation key | Up to 100 latest conversations belonging to the caller |
| PUT `/push-token` | Registered Bearer installation key | Bind or disable the caller's FCM token; one installation per current account |
| GET `/profiles/{id}` | Registered Bearer installation key | Resolve an active public profile for a notification's chat link |

Public response fields: id, name, username, is_active. No phone, email, token or key hash is returned. The server never receives the address book.

The new-VPS unit sets `SALOM_REQUIRE_POSTGRES=1` and `SALOM_REQUIRE_REDIS=1`, so production fails closed if either service is missing. PostgreSQL retains message history until explicit user deletion; each client synchronizes by monotonically increasing message IDs. Redis supplies shared per-minute rate limits, short-lived public-profile cache, 45-second presence TTLs, and Pub/Sub fan-out for WebSocket events between Uvicorn workers. Keep Uvicorn bound to loopback and expose it only through Nginx. SQLite remains the test/development backend. Messages are not end-to-end encrypted. Sender identity comes from the authenticated session, never a request-supplied sender ID. Android uses WebSocket events while connected and HTTP history synchronization after disconnects; FCM remains the background notification path.

Server FCM credentials live at `/etc/salom-api/firebase-admin.json`, root-owned and group-readable only by `salom-api` (0640 inside a 0750 directory). They never enter the APK or Git. Android's `google-services.json` is client configuration, not a server private key. Install the pinned Python dependencies before deploying; `push.py` is deployed alongside `app.py`.

Each new message inserts an outbox row in the same SQLite transaction. The single service worker sends HTTP v1 notification+data payloads, using a one-day TTL, high priority, private lockscreen visibility and a stable per-message notification tag. Network failures retry with backoff; unregistered tokens are removed. Delivery is at least once; a stable tag replaces a repeated notification. Google acceptance is not proof of device display or reading. Existing message POST retries do not enqueue another outbox row.

`test_push.py` uses an isolated temporary SQLite database and fake HTTP responses to check queue idempotency, payload routing, network retry, token removal, disable and binding isolation. `test_realtime.py` and `test_realtime_endpoint.py` cover WebSocket authorization, subscriptions and event routing. `ui_push_probe.py` creates a controlled sender for physical Android tests, then removes its profile, messages and outbox rows with `cleanup`. Force-stopping an app from Android settings prevents FCM delivery until the user opens it again; ordinary backgrounding is different.

`monitoring.py` periodically logs aggregate HTTP status classes and latency, process CPU time, RSS, descriptor count and active WebSocket count to Uvicorn's journal. It intentionally omits request paths, message content, identifiers and credentials. `load_test_realtime.py --clients 25 50 100` runs against temporary local SQLite and an in-process test hub; it does not contact production and is not a VPS/network benchmark.

`qa_messaging_live.py`, `qa_auth_live.py`, and `qa_media_live.py` use public HTTPS and create/delete synthetic production-side QA fixtures. Run them only as deliberate live QA tasks; they are intentionally named `qa_*` so `unittest discover -p 'test_*.py'` cannot execute them. `ui_messaging_probe.py create @username` creates a temporary controlled peer that replies only to the exact QA text `Salom050check`. Run its `cleanup` command after Android testing to remove that peer and cascade-delete its messages. No real user's conversation is used by these tests.

`initialize_postgres.py` creates the new-VPS schema only when `SALOM_DATABASE_URL` points to the explicitly named empty database `salom_prod`; it refuses any non-empty schema and imports no SQLite rows. `deploy.py` is a read-only release-bundle preflight. It verifies required files, Python syntax and PostgreSQL driver declarations; it does not modify the VPS, reload Nginx or restart services. A production rollout still needs a fresh off-server encrypted backup and a tested rollback.

`../ops/deploy.sh` is the prepared new-host release wrapper. Install it root-owned at `/usr/local/sbin/salom-deploy` and allow only that command to the `salom-deploy` user. It requires a clean private Git checkout and a full commit SHA reachable from `origin/main`; it builds a release from Git archive, swaps the application symlink, restarts the API and verifies `/health`. It does not run database migrations or change DNS. The target must already have its protected environment file, PostgreSQL schema/data, service unit and media directory prepared.

`migrate_sqlite_to_postgres_prod.py` is a one-shot production snapshot importer. It only reads a consistent backup, refuses to overwrite an existing PostgreSQL schema, requires database name and confirmation `salom_prod`, and compares counts/checksums in a single transaction. Do not run it against the live SQLite file or during active writes; use a cutover-window snapshot.

`smoke_test.py` checks two independent identities over public HTTPS and removes only its own generated fixtures. `ui_probe.py create @username` checks a real target from an independent temporary account and publishes a clearly named QA fixture for Android UI testing; run `ui_probe.py cleanup` afterward. Never leave QA fixtures in the public directory.

`requirements.lock` records the installed versions. Runtime SQLite data and installation credentials are not committed to Git. Release APK signing requires the existing SALOM upload key; Gradle fails closed when signing configuration is absent. See `../docs/release-signing.md`.

`backup.py` creates a consistent root-private SQLite snapshot in `/var/backups/salom-directory` without interrupting the running API. It is a manual local backup helper; recurring encrypted off-server database/media backup and a verified restore are still release gates.
