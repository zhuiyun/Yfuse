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
touch /tmp/yfuse-tree-active
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell settings get secure accessibility_interactive_ui_timeout_ms >qa-emby-evidence/control-visibility-timeout-ms.txt
adb reverse tcp:18196 tcp:18196
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm dismiss-keyguard
if [[ "$1" == upgrade ]]; then
 /usr/bin/python3 qa-functional/upgrade.py
 exit
fi
rm -f app-apk/9eef*.apk
test "$(find app-apk -name '*.apk' | wc -l)" = 1
set +e
if [[ "$1" == standard ]]; then
 /usr/bin/python3 qa-functional/external.py
elif [[ "$1" == hls ]]; then
 /usr/bin/python3 qa-functional/burst-hls.py
else
 /usr/bin/python3 qa-functional/burst.py
fi
rc=$?
/usr/bin/python3 qa-functional/gather-burst.py
exit "$rc"
