set -euo pipefail
bash qa-volume/build-driver.sh
touch /tmp/yfuse-tree-active
adb install -r -g preview/composeApp-release.apk
adb shell pm grant com.yfuse android.permission.POST_NOTIFICATIONS || true
adb shell wm size 720x1280
adb shell wm density 260
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
/usr/bin/python3 qa-title-direct/direct.py "$1" "$2"
