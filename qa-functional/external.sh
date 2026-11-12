set -eu
sock="$RUNNER_TEMP/seek-emby.sock"
mkdir -p qa-emby-local qa-emby-evidence
sudo unshare -n -- /usr/bin/python3 qa-scrub/isolated.py qa-emby-bin/opt/emby-server qa-media qa-emby-local "$sock" > qa-emby-start.log 2>&1 &
service_pid=$!
trap 'sudo kill "$service_pid" 2>/dev/null || true' EXIT
for ((i=0;i<130;i++)); do
 if [[ -S "$sock" ]]; then break; fi
 if ! sudo kill -0 "$service_pid" 2>/dev/null; then cat qa-emby-start.log; exit 1; fi
 sleep 1
done
test -S "$sock"
/usr/bin/python3 qa-scrub/isolated.py relay "$sock" > /dev/null 2>&1 &
bash qa-functional/driver-build.sh
set +e
/usr/bin/python3 qa-functional/external.py
scrub_rc=$?
/usr/bin/python3 qa-functional/recovery.py
exit "$scrub_rc"
