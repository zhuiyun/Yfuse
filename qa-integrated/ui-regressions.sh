set -euo pipefail
test "$GITHUB_ACTIONS" = true
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "$1"
mkdir -p results
trap 'adb logcat -d -b crash > results/crash.txt; adb pull /sdcard/Android/data/com.yfuse.validation/files/. results/device-evidence/ || true' EXIT
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
adb install -r -g preview/app.apk
adb install -r -g preview/tests.apk
adb shell am instrument -w -r -e notAnnotation com.yfuse.core2.android.ExternalMediaFixture com.yfuse.validation.test/androidx.test.runner.AndroidJUnitRunner | tee results/instrumentation.txt
python3 qa-integrated/check-instrumentation.py results/instrumentation.txt
