#!/usr/bin/env bash
# Online SQLite snapshots of the service databases: verified, compressed, encrypted when a
# recipient is configured, copied off-host only when encrypted, and pruned by count and size.
#
# Runs from yfuse-backup.timer as the dedicated yfuse-backup user, which reads the databases
# through the yfuse group and owns the backup directory and the off-host credentials; the
# service user can neither read the off-host key nor rewrite past backups. All databases use
# WAL, so `.backup` is the only correct way to copy them while the service is up.
#
# Settings (environment, usually /etc/yfuse-watch/backup.env):
#   YFUSE_DATA_DIR               databases to snapshot (default /var/lib/yfuse)
#   YFUSE_BACKUP_DIR             where snapshots are kept (default /var/backups/yfuse)
#   YFUSE_BACKUP_AGE_RECIPIENTS  file of age recipients (one per line); enables encryption
#   YFUSE_BACKUP_REMOTE          rsync destination (user@host:/path); requires encryption
#   YFUSE_BACKUP_KEEP_COUNT      snapshots kept per database (default 14)
#   YFUSE_BACKUP_MAX_TOTAL_MB    cap on the whole backup directory (default 2048)
set -euo pipefail
umask 077

data_dir="${YFUSE_DATA_DIR:-/var/lib/yfuse}"
backup_dir="${YFUSE_BACKUP_DIR:-/var/backups/yfuse}"
recipients="${YFUSE_BACKUP_AGE_RECIPIENTS:-}"
remote="${YFUSE_BACKUP_REMOTE:-}"
keep_count="${YFUSE_BACKUP_KEEP_COUNT:-14}"
max_total_mb="${YFUSE_BACKUP_MAX_TOTAL_MB:-2048}"
stamp="$(date -u +%Y%m%d-%H%M%S)"

[[ "$keep_count" =~ ^[1-9][0-9]*$ ]] || { echo "YFUSE_BACKUP_KEEP_COUNT must be a positive integer" >&2; exit 2; }
[[ "$max_total_mb" =~ ^[1-9][0-9]*$ ]] || { echo "YFUSE_BACKUP_MAX_TOTAL_MB must be a positive integer" >&2; exit 2; }
if [[ -n "$recipients" ]]; then
  [[ -r "$recipients" ]] || { echo "age recipients file $recipients is not readable" >&2; exit 2; }
  command -v age >/dev/null || { echo "age is not installed but YFUSE_BACKUP_AGE_RECIPIENTS is set" >&2; exit 2; }
fi
# Fail before any work rather than after: a plaintext copy must never leave the host.
if [[ -n "$remote" && -z "$recipients" ]]; then
  echo "refusing off-host copy to $remote without encryption: set YFUSE_BACKUP_AGE_RECIPIENTS" >&2
  exit 3
fi

install -d -m 0700 "$backup_dir"
work="$(mktemp -d "$backup_dir/.work.XXXXXX")"
trap 'rm -rf "$work"' EXIT

made=()
for name in account calendar migration-relay qoe watch-state; do
  source="$data_dir/$name.db"
  [[ -f "$source" ]] || continue
  snapshot="$work/$name-$stamp.db"
  sqlite3 "$source" ".backup '$snapshot'"
  if ! sqlite3 "$snapshot" "PRAGMA integrity_check;" | grep -Fxq ok; then
    echo "integrity check failed for $name" >&2
    exit 1
  fi
  gzip -9 --no-name "$snapshot"
  artifact="$snapshot.gz"
  if [[ -n "$recipients" ]]; then
    age --encrypt --recipients-file "$recipients" --output "$artifact.age" "$artifact"
    rm -f "$artifact"
    artifact="$artifact.age"
  fi
  chmod 0600 "$artifact"
  mv "$artifact" "$backup_dir/"
  made+=("$backup_dir/$(basename "$artifact")")
  echo "backed up $source -> $backup_dir/$(basename "$artifact")"
done

if [[ -n "$remote" && ${#made[@]} -gt 0 ]]; then
  rsync -a --chmod=F0600 "${made[@]}" "$remote"/
fi

# Retention: the newest $keep_count snapshots of each database, then the oldest snapshots of any
# database until the directory fits in $max_total_mb. Tonight's set is never removed.
for name in account calendar migration-relay qoe watch-state; do
  find "$backup_dir" -maxdepth 1 -type f -name "$name-*.db*" -printf '%T@ %p\n' |
    sort -rn | tail -n +"$((keep_count + 1))" | cut -d' ' -f2- |
    while IFS= read -r old; do rm -f -- "$old"; done
done
max_bytes=$((max_total_mb * 1024 * 1024))
while :; do
  total="$(find "$backup_dir" -maxdepth 1 -type f -name '*.db*' -printf '%s\n' | awk '{s += $1} END {print s + 0}')"
  (( total > max_bytes )) || break
  oldest="$(find "$backup_dir" -maxdepth 1 -type f -name '*.db*' ! -name "*-$stamp.db*" -printf '%T@ %p\n' |
    sort -n | head -n 1 | cut -d' ' -f2-)"
  if [[ -z "$oldest" ]]; then
    echo "backup set of $stamp alone exceeds YFUSE_BACKUP_MAX_TOTAL_MB" >&2
    break
  fi
  rm -f -- "$oldest"
done
