set -eu
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm dismiss-keyguard
if [[ "$1" == settings ]]; then
 /usr/bin/python3 qa-functional/smoke.py "${2%%.*}"
 exit
fi
sock="$RUNNER_TEMP/functional-media.sock"
mkdir -p qa-emby-local feature-evidence
sudo unshare -n -- /usr/bin/python3 qa-scrub/isolated.py qa-emby-bin/opt/emby-server qa-media qa-emby-local "$sock" > qa-server.log 2>&1 &
server=$!
trap 'sudo kill "$server" 2>/dev/null || true' EXIT
for n in $(seq 1 120); do [[ ! -S "$sock" ]] || break; sleep 1; done
test -S "$sock"
/usr/bin/python3 qa-scrub/isolated.py relay "$sock" >/dev/null 2>&1 &
adb reverse tcp:18196 tcp:18196
adb install -r -g "$(find app-apk -name '*.apk' | head -1)"
/usr/bin/python3 qa-functional/features.py
adb shell dumpsys activity exit-info com.yfuse.validation > feature-evidence/process-exit.txt
