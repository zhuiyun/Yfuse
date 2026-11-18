set -euo pipefail
test "$GITHUB_ACTIONS" = true
sudo apt-get update -qq
sudo apt-get install -y ffmpeg python3-pil python3-numpy
bash qa-arm-dual/redroid.sh
export ANDROID_SERIAL=127.0.0.1:15555
export PATH="$RUNNER_TEMP/qa-arm-tools:$PATH"
export OPENBLAS_NUM_THREADS=1
mkdir -p results/original-volume
(cd candidate && sha256sum -c apk.sha256)
grep -Fx 'app=94ec647fac5132b5f3ee92e1304b98f57084e078' candidate/provenance.txt
cp candidate/apk.sha256 candidate/provenance.txt results/original-volume/
adb install -r -g candidate/composeApp-release.apk
adb install -r candidate/driver.apk
adb shell wm size 720x1280
adb shell wm density 260
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put secure accessibility_interactive_ui_timeout_ms 60000
adb shell wm dismiss-keyguard
touch /tmp/yfuse-tree-active
/usr/bin/python3 qa-arm-original/original_volume.py
