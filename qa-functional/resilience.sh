set -euo pipefail
api=$1
seed=$2
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "$api"
mkdir -p qa-emby-local results
sock="$RUNNER_TEMP/resilience-emby.sock"
sudo unshare -n -- /usr/bin/python3 qa-scrub/isolated.py qa-emby-bin/opt/emby-server qa-media qa-emby-local "$sock" > "$RUNNER_TEMP/emby.log" 2>&1 &
server_pid=$!
trap 'sudo kill "$server_pid" 2>/dev/null || true' EXIT
for i in $(seq 1 130); do
  if [[ -S "$sock" ]]; then break; fi
  sudo kill -0 "$server_pid"
  sleep 1
done
test -S "$sock"
bash qa-functional/driver-build.sh
touch /tmp/yfuse-tree-active
adb install -r -g preview/composeApp-release.apk
adb reverse tcp:18196 tcp:18196
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
/usr/bin/python3 qa-functional/resilience.py "$sock" "$api" "$seed"
