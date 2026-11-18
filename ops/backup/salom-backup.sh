#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

DATA=/srv/salom-data
BACKUP_DIR=$DATA/backups
RECIPIENT_FILE=/etc/salom-backup/recipient
MIN_FREE_KIB=$((5 * 1024 * 1024))
LOCK=/run/lock/salom-backup.lock

fail() { printf 'salom-backup: ERROR: %s\n' "$*" >&2; exit 1; }
free_kib() { /usr/bin/df -Pk "$DATA" | /usr/bin/awk 'NR==2 {print $4}'; }

[[ ${EUID:-$(/usr/bin/id -u)} -eq 0 ]] || fail 'must run as root'
[[ -s $RECIPIENT_FILE ]] || fail 'age recipient is missing'
[[ -d $BACKUP_DIR ]] || fail 'backup directory is missing'
[[ $(free_kib) =~ ^[0-9]+$ ]] || fail 'cannot read free space'
(( $(free_kib) >= MIN_FREE_KIB )) || fail 'less than 5 GiB free on SALOM data disk'

exec 9>"$LOCK"
/usr/bin/flock -n 9 || fail 'another backup is running'

stamp=$(/usr/bin/date -u +%Y%m%dT%H%M%SZ)
stage=$(/usr/bin/mktemp -d "$BACKUP_DIR/.stage-$stamp.XXXXXX")
tmp="$BACKUP_DIR/.salom-$stamp.tar.gz.age.tmp"
final="$BACKUP_DIR/salom-$stamp.tar.gz.age"
cleanup() { /usr/bin/rm -rf -- "$stage"; /usr/bin/rm -f -- "$tmp"; }
trap cleanup EXIT

/usr/bin/install -d -o postgres -g postgres -m 0700 "$stage/db"
db_password=$(/usr/bin/python3 -c 'import os, urllib.parse; print(urllib.parse.unquote(urllib.parse.urlsplit(os.environ["SALOM_DATABASE_URL"]).password or ""))')
[[ -n $db_password ]] || fail 'database URL does not contain a password'
PGPASSWORD=$db_password /usr/bin/pg_dump --host=127.0.0.1 --username=salom_app \
  --format=custom --no-owner --file="$stage/db/salom_prod.dump" salom_prod
unset db_password
/usr/bin/chown root:root "$stage/db/salom_prod.dump"
/usr/bin/chmod 0600 "$stage/db/salom_prod.dump"

/usr/bin/python3 /usr/local/libexec/salom-backup-archive.py \
  --stage "$stage" --output "$tmp" --recipient-file "$RECIPIENT_FILE"

[[ -s $tmp ]] || fail 'encrypted archive is empty'
/usr/bin/chmod 0600 "$tmp"
/usr/bin/mv -- "$tmp" "$final"

# Retain the most recent 14 daily archives. The current archive is kept even if
# disk pressure rises; the next run will fail closed below the free-space limit.
/usr/bin/find "$BACKUP_DIR" -maxdepth 1 -type f -name 'salom-*.tar.gz.age' \
  -mtime +13 -delete

remaining=$(free_kib)
(( remaining >= MIN_FREE_KIB )) || fail 'backup completed but less than 5 GiB remains'
printf 'salom-backup: encrypted archive created: %s; free_kib=%s\n' "$final" "$remaining"
