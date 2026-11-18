# Release signing for SALOM Android

The historical SALOM upload key could not be found in the searched project folders, Downloads, OneDrive documents, or previous VPS. A new permanent signing key is managed outside the repository. Android will reject this new certificate as an in-place update to APKs signed with the lost historical key; distribute the new signed build only with a clear reinstall/migration plan.

## Supply the existing key to Gradle

The key is stored at `%LOCALAPPDATA%\SALOM\signing\salom-release.jks`. A DPAPI-encrypted copy and encrypted signing configuration are stored in the user's OneDrive SALOM backup folder. `tools/build-android-release.ps1` decrypts the user-scoped signing configuration into process environment variables, builds the release APK, and verifies its signature. Do not commit the keystore, encrypted backup, passwords, or a properties file containing them. The build fails closed if the protected profile is missing.

## Check before building

1. Verify the configured certificate fingerprint against the local keystore.
2. Build with `tools/build-android-release.ps1` after the backup/restore check.
3. Verify the APK package name, semantic `versionName`, monotonically increasing `versionCode`, and signing certificate. Keep the release metadata's `version` equal to the APK `versionName`; put the human-readable label (for example, `AllowCookie`) in `displayName`.
4. Keep the APK unpublished until the separately approved release step and reinstall instructions are ready.

The download deployment script now requires `/tmp/salom-deploy/release.json` beside `salom-<version>.apk`. It publishes that descriptor into `version.json` and refuses a `versionCode` rollback or replacement of bytes already published under an immutable version. Populate the descriptor from the APK you are actually releasing:

```json
{
  "version": "0.15.1",
  "displayName": "CallCookie",
  "versionCode": 21,
  "minSdk": 24,
  "releaseNotes": ["One-to-one voice calls with incoming-call and missed-call notifications"]
}
```
