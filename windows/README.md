# SALOM Desktop client

The first WPF client targets Windows 11 x64 with .NET 10. It provides the SALOM dark interface, Google sign-in flow, people search, text chats, a local SQLite history, and a per-Windows-user DPAPI-protected session token.

## Configuration

Set these values in the Windows user's environment before starting the client:

- `SALOM_API_BASE_URL` — HTTPS base ending in `/salom/api/`; defaults to `https://mukimov.site/salom/api/`.
- `SALOM_GOOGLE_CLIENT_ID` — Google OAuth desktop client ID.
- `SALOM_GOOGLE_CLIENT_SECRET` — OAuth desktop client secret used only for the authorization-code exchange. Desktop OAuth clients are public clients; this value is not a server secret and must not be treated as one.
- `SALOM_FIREBASE_WEB_API_KEY` — Firebase Identity Toolkit API key for the same Firebase project.

The desktop OAuth client must belong to the Firebase project and Google provider configuration used by SALOM. The Android `google-services.json` does not contain the desktop OAuth client settings. A real account sign-in still needs owner-side Google/Firebase setup and a live-backend verification.

## Build

```powershell
dotnet restore windows/Salom.Desktop/Salom.Desktop.csproj
dotnet publish windows/Salom.Desktop/Salom.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

The publish output is a standalone executable, but is not code-signed. The current client does not yet implement attachments, voice messages, calls, Windows notifications, WebSocket delivery, or a setup/uninstaller package. Do not describe it as a complete Windows messenger until those features and integration tests are finished.
