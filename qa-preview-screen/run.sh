set -euo pipefail
bash qa-functional/driver-build.sh
touch /tmp/yfuse-tree-active
adb install -r -g preview/composeApp-release.apk
adb reverse tcp:18382 tcp:18382
adb shell wm size 720x1280
adb shell wm density 260
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
/usr/bin/python3 qa-preview-screen/test.py
