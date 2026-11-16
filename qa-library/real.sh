set -euo pipefail
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 35
adb install -r -g preview/composeApp-release.apk
bash qa-functional/driver-build.sh
touch /tmp/yfuse-tree-active
adb reverse tcp:18628 tcp:18628
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
/usr/bin/python3 qa-library/real.py
