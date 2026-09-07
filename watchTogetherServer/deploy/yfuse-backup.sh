#!/usr/bin/env bash
# Online SQLite snapshots of the account and calendar databases, verified and pruned.
# Run by yfuse-backup.timer as the yfuse user; both databases use WAL, so `.backup` is the
# only correct way to copy them while the service is up.
set -euo pipefail

data_dir="${YFUSE_DATA_DIR:-/var/lib/yfuse}"
backup_dir="${YFUSE_BACKUP_DIR:-$data_dir/backups}"
keep_days="${YFUSE_BACKUP_KEEP_DAYS:-14}"
stamp="$(date -u +%Y%m%d-%H%M%S)"

install -d -m 0700 "$backup_dir"
for name in account calendar migration-relay; do
  source="$data_dir/$name.db"
  [[ -f "$source" ]] || continue
  target="$backup_dir/$name-$stamp.db"
  sqlite3 "$source" ".backup '$target'"
  if ! sqlite3 "$target" "PRAGMA integrity_check;" | grep -Fxq ok; then
    echo "integrity check failed for $target" >&2
    rm -f "$target"
    exit 1
  fi
  chmod 0600 "$target"
  echo "backed up $source -> $target"
done

# Off-host copy, when configured: any rsync-compatible destination (user@host:/path).
if [[ -n "${YFUSE_BACKUP_REMOTE:-}" ]]; then
  rsync -a --chmod=F0600 "$backup_dir"/*-"$stamp".db "$YFUSE_BACKUP_REMOTE"/
fi

find "$backup_dir" -name '*.db' -type f -mtime +"$keep_days" -delete
