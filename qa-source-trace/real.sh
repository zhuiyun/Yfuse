set -euo pipefail
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 35
adb install -r -g preview/composeApp-release.apk
adb root >/dev/null
adb wait-for-device
cp qa-source-trace/Driver.java qa-volume/input-driver.java
bash qa-source-trace/build-driver.sh
touch /tmp/yfuse-tree-active
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
/usr/bin/python3 qa-source-trace/real.py
