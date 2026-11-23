set -euo pipefail
bash qa-functional/driver-build.sh
touch /tmp/yfuse-tree-active
adb install -r -g "$(cat "$RUNNER_TEMP/install-apk")"
adb shell wm dismiss-keyguard
adb shell settings put secure immersive_mode_confirmations confirmed
/usr/bin/python3 qa-menus/screens.py "$1"
