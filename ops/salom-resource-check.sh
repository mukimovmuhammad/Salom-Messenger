#!/bin/sh
set -eu
root_used=$(df -P / | awk 'NR==2 {print $5}' | tr -d '%')
data_used=$(df -P /srv/salom-data | awk 'NR==2 {print $5}' | tr -d '%')
mem=$(free -m | awk '/^Mem:/ {printf "%sMiB available", $7}')
api=$(systemctl is-active salom-api 2>/dev/null || true)
pg=$(systemctl is-active postgresql 2>/dev/null || true)
redis=$(systemctl is-active redis-server 2>/dev/null || true)
backup_timer=$(systemctl is-active salom-backup.timer 2>/dev/null || true)
backup_mtime=$(find /srv/salom-data/backups -maxdepth 1 -type f -name 'salom-*.tar.gz.age' -printf '%T@\n' 2>/dev/null | sort -nr | head -n 1)
if [ -n "$backup_mtime" ]; then
    backup_age_seconds=$(($(date +%s) - ${backup_mtime%.*}))
else
    backup_age_seconds=999999
fi
line="root_disk=${root_used}% data_disk=${data_used}% memory=${mem} salom_api=${api} postgresql=${pg} redis=${redis} backup_timer=${backup_timer} backup_age_seconds=${backup_age_seconds}"
if [ "$root_used" -ge 85 ] || [ "$data_used" -ge 85 ] || [ "$backup_timer" != active ] || [ "$backup_age_seconds" -gt 97200 ]; then logger -p daemon.warning -t salom-resource "$line"; else logger -t salom-resource "$line"; fi
