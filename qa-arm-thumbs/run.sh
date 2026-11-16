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
/usr/bin/python3 qa-arm-matrix/engine.py "$QA_ENGINE"
/usr/bin/python3 qa-arm-thumbs/test.py
