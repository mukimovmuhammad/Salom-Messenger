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

The TURN service itself is not installed or deployed by this change. A production rollout still needs coturn, UDP/TCP reachability, TLS for `turns:` where used, and a connection test from the intended mobile networks.

## Android behavior and rollout notes

Incoming full-screen call UI depends on Android notification and full-screen-intent settings. Android 13+ also requires notification permission. Microphone capture begins only after the user starts or accepts a call. Android 14+ may restrict background microphone service starts, so the user-visible call action must start the service while SALOM is visible.

The backend creates the additive tables during startup. On PostgreSQL this uses `CREATE TABLE IF NOT EXISTS`; it does not rewrite existing message/profile data. One API worker is still required because the existing WebSocket hub is process-local. Before production use, build and install the Android APK, test two real accounts on mobile data and Wi-Fi, and configure TURN. This implementation has not been built, device-tested, or deployed as part of the source change.
