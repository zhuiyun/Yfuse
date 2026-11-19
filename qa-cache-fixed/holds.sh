set -euo pipefail
test "$GITHUB_ACTIONS" = true
abi=$(adb shell getprop ro.product.cpu.abilist64 | tr -d '\r')
test "${abi%%,*}" = arm64-v8a
adb root || true
adb wait-for-device
[[ "$(adb shell id)" == uid=0* ]]
adb install -r -g preview/composeApp-release.apk
adb install -r qa-driver/driver.apk
adb reverse tcp:18382 tcp:18382
adb shell wm size 720x1280
adb shell wm density 260
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
touch /tmp/yfuse-tree-active
status=0
trials=(held-stalled)
if [[ "${QA_CACHE_PAIRED_CONTROLS:-false}" == true ]]; then
  trials+=(idle-stalled held-paced)
fi
for trial in "${trials[@]}"; do
  # Each observation starts with a fresh cache and the same native engine.
  adb shell pm clear com.yfuse | tr -d '\r' | grep -Fx 'Success'
  /usr/bin/python3 qa-arm-matrix/engine.py "$QA_ENGINE"
  QA_CACHE_TRIAL="$trial" /usr/bin/python3 qa-cache-fixed/holds.py || status=1
done
exit "$status"
