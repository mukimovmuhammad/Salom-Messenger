# SALOM encrypted backups

The VPS creates a daily age-encrypted archive containing a PostgreSQL custom-format dump, `/etc/salom-api`, Redis/PostgreSQL service configuration, the SALOM systemd unit, `/srv/salom-data/app`, and user media. Archives live on `/srv/salom-data/backups`, are mode `0600`, and older-than-14-day snapshots are rotated. The job refuses to start when the data disk has under 5 GiB free. `ops/systemd/salom-backup.timer` runs the job daily at 02:30 UTC with a randomized delay.

Encryption uses a recipient public key in `/etc/salom-backup/recipient`; the matching identity must never be stored on the VPS. On the configured Windows machine it is protected with DPAPI at `C:\Users\HP\AppData\Local\SALOM\backup-age-identity.dpapi`. A DPAPI-wrapped copy is also kept in the approved Google Drive backup folder. DPAPI recovery is tied to the same Windows account/profile: that Drive copy is not portable to a replacement PC. Before treating this as full disaster recovery, export a portable, passphrase-protected recovery identity and store its passphrase separately in the owner's password manager.

The Drive connector can receive encrypted archives, but it is an interactive connector and does not provide unattended upload credentials to the VPS. The first encrypted snapshot was uploaded and verified by download and isolated PostgreSQL restore; later daily snapshots still require an off-site upload. Do not put an age identity or any unencrypted dump in Git or in Drive.

## Restore outline

1. Download the `.tar.gz.age` artifact from Drive to an isolated, access-controlled machine.
2. Decrypt with the matching age identity, then inspect the archive contents before extracting.
3. Restore the PostgreSQL dump into a separately named empty test database, not `salom_prod`.
4. Restore service configuration and media only into a staging directory; validate ownership and permissions before replacing live files.
5. Verify table counts, application health, and a small media-file checksum before approving a production restore.

The scheduled VPS job protects against accidental server loss only after an off-site copy exists. Until unattended Google Drive upload and a portable recovery identity are configured, this is not a complete disaster-recovery system.
