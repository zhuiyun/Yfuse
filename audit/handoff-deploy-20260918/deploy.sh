#!/usr/bin/env bash
set -euo pipefail
archive=$1
expected_hash=$2
expected_sha=$3
stamp=$(date -u +%Y%m%d-%H%M%S)
release="/opt/yfuse-watch/releases/${stamp}-${expected_sha:0:8}-handoff"
previous=$(readlink -f /opt/yfuse-watch/current)
backup="/var/lib/yfuse/backups/pre-handoff-${stamp}"
test "$(id -u)" = 0
test -L /opt/yfuse-watch/current
test -x "$previous/bin/watchTogetherServer"
test "$(sha256sum "$archive" | cut -d ' ' -f 1)" = "$expected_hash"
test ! -e "$release"
test ! -e /opt/yfuse-watch/current.handoff-new
install -d -m 0755 "$release"
tar xzf "$archive" -C "$release" --strip-components=1
chown -R root:root "$release"
chmod 0755 "$release/bin/watchTogetherServer"
test -s "$release/lib/watchTogetherServer.jar" || test -n "$(find "$release/lib" -maxdepth 1 -name 'watchTogetherServer-*.jar' -print -quit)"
install -d -m 0700 "$backup"
python3 - "$backup" <<'PY'
import pathlib, sqlite3, subprocess, sys
backup = pathlib.Path(sys.argv[1])
for source in sorted(pathlib.Path('/var/lib/yfuse').glob('*.db')):
    target = backup / source.name
    subprocess.check_call(['sqlite3', '-cmd', '.timeout 10000', str(source), ".backup '" + str(target) + "'"])
    with sqlite3.connect(str(target)) as dst:
        assert dst.execute('PRAGMA integrity_check').fetchall() == [('ok',)], source.name
    target.chmod(0o600)
    print('backup_verified=' + source.name, flush=True)
PY
printf '%s\n' "$previous" > "$backup/previous-release.txt"
printf '%s\n' "$release" > "$backup/new-release.txt"
printf '%s\n' "$expected_hash" > "$backup/archive-sha256.txt"
switched=0
rollback() {
    result=$?
    if [ "$result" -ne 0 ] && [ "$switched" = 1 ]; then
        ln -s "$previous" /opt/yfuse-watch/current.handoff-rollback
        mv -Tf /opt/yfuse-watch/current.handoff-rollback /opt/yfuse-watch/current
        systemctl restart yfuse-update
        printf 'ROLLED_BACK_TO=%s\n' "$previous"
    fi
    exit "$result"
}
trap rollback EXIT
ln -s "$release" /opt/yfuse-watch/current.handoff-new
mv -Tf /opt/yfuse-watch/current.handoff-new /opt/yfuse-watch/current
switched=1
systemctl restart yfuse-update
systemctl is-active --quiet yfuse-update
test "$(curl --fail --silent --show-error http://127.0.0.1:8080/health)" = ok
curl --fail --silent --show-error http://127.0.0.1:8080/watch/version | python3 -c 'import json,sys; assert json.load(sys.stdin)["gitSha"] == sys.argv[1]' "$expected_sha"
for base in http://127.0.0.1:8080 https://47.112.219.60; do
    proxy_headers=()
    # The account API requires HTTPS, including loopback requests through its trusted proxy.
    if [ "$base" = http://127.0.0.1:8080 ]; then
        proxy_headers=(-H 'X-Forwarded-Proto: https')
    fi
    for path in /api/v1/account/handoff /api/v1/account/handoff/heartbeat /api/v1/account/profile; do
        request_args=()
        if [ "$path" = /api/v1/account/handoff/heartbeat ]; then
            request_args=(-H 'Content-Type: application/json' --data '{}')
        fi
        status=$(curl --max-time 15 --silent --show-error --output /dev/null --write-out '%{http_code}' "${proxy_headers[@]}" "${request_args[@]}" "$base$path")
        printf 'verified_endpoint=%s%s status=%s\n' "$base" "$path" "$status"
        test "$status" = 401
    done
done
curl --fail --silent --show-error https://47.112.219.60/health
printf '\nDEPLOYED=%s\nPREVIOUS=%s\nBACKUP=%s\n' "$release" "$previous" "$backup"
