# SALOM CallCookie voice calls

CallCookie adds one-to-one, audio-only WebRTC calls. SALOM authenticates the invite and relays short SDP/ICE signaling messages between the two call participants. Audio is sent by WebRTC; SALOM does not record or store call audio.

## Included

- Authenticated call creation, accept, decline, cancel, end, signaling, expiry, and missed-call counts.
- Participant checks on every call state and signaling request; signaling payloads are limited to 12 KB.
- High-priority Firebase data pushes for incoming calls and a later silent missed-call notification with the unread count.
- Android incoming-call notification actions, in-app ringtone, microphone permission, audio mute/speaker controls, and a foreground microphone service during an active call.
- Short lived TURN credentials generated from `SALOM_TURN_URLS` and `SALOM_TURN_SECRET`.
- Additive SQLite/PostgreSQL schema and migration-rehearsal support for older SQLite snapshots without call tables.

## TURN configuration

STUN is included as a connectivity fallback. Calls may fail on carrier-grade NAT, restrictive Wi-Fi, or firewalls unless a TURN relay is configured. The API only returns TURN credentials when both variables are present:

- `SALOM_TURN_URLS`: comma-separated `turn:`/`turns:` URLs, including transport/port where needed.
- `SALOM_TURN_SECRET`: the shared `static-auth-secret` configured in coturn. Keep it only in the server secret store; never place it in Android or Git.

For Cloudflare Realtime TURN, configure `SALOM_CLOUDFLARE_TURN_KEY_ID` and `SALOM_CLOUDFLARE_TURN_TOKEN` in a protected systemd environment file on the API host. SALOM issues credentials only for authenticated profiles, rate-limits requests, and keeps the existing client response format. The credential lifetime covers the server's four-hour call limit plus five minutes of setup. On a provider failure the API falls back to the configured coturn relay, or to the built-in STUN servers.

Before switching traffic, verify that the VPS can reach `rtc.live.cloudflare.com` on HTTPS and that a client can establish a Cloudflare relay allocation over TLS on TCP 443. Test Android and Windows on separate networks afterwards. To revert, remove the Cloudflare systemd drop-in, reload systemd and restart the API. Check Cloudflare Realtime usage against the account's included allowance.

### Rotate the Cloudflare TURN key

1. Create a second TURN key in Cloudflare Realtime. Transfer the new key and token through a protected file. Keep the token out of command arguments and shell history.
2. Stage a root-only environment file and verify with `issue_turn_servers` that the new key issues credentials for `turns:turn.cloudflare.com:443?transport=tcp`. Verify a TLS relay allocation using those temporary credentials. Print only the outcome.
3. With an approved maintenance window, back up `/etc/salom-api/cloudflare-turn.env` under a root-only directory. Atomically replace it with the staged file (owner root, mode `0600`) and restart `salom-api`. Check public and internal health and verify that an authenticated client can start a new relay call.
4. Restore the previous environment file and restart the service if checks fail. Once the new key is verified, revoke the previous key in Cloudflare and remove temporary secret files. Review Cloudflare usage for unexpected traffic.

## Android behavior and rollout notes

Incoming full-screen call UI depends on Android notification and full-screen-intent settings. Android 13+ also requires notification permission. Microphone capture begins only after the user starts or accepts a call. Android 14+ may restrict background microphone service starts, so the user-visible call action must start the service while SALOM is visible.

The backend creates additive tables during startup. The local WebSocket hub is process-local. When Redis is configured, the backend relays events to the local hubs of other workers. For a network compatibility check, test two real accounts on mobile data and Wi-Fi and verify the TURN relay.

## Диагностика Galaxy A15

Для проверки нужны два участника и один согласованный звонок в каждом направлении. Перед первым звонком запишите модель, версию Android, SALOM, тип сети и состояние разрешения микрофона. Камера нужна только для отдельного видеотеста.

На каждом подключённом Android выполните `adb devices -l` и `adb shell getprop ro.build.version.release`. Версию пакета и выданные разрешения сверьте в `adb shell dumpsys package com.mukimov.salom`. Затем выполните `adb logcat -v time SalomCall:I AndroidRuntime:E '*:S'`, воспроизведите вызов, остановите команду и сопоставьте строки двух клиентов по Call ID. Для отчёта нужны переходы signaling, remote/local SDP, ICE gathering и media, точный симптом с каждой стороны и результат повторного звонка.

Если оба клиента обменялись SDP, а ICE не подключился, повторите ту же пару вызовов на Wi-Fi и мобильной сети. При доступе к тестовому окружению отдельно измерьте установление соединения через настроенные TURN UDP и TCP/TLS. Сравните успешный вызов A16 и неуспешный A15 на той же сети. Не пересылайте SDP, ICE-кандидаты целиком, TURN-пароли, IP участников и текст переписки.
