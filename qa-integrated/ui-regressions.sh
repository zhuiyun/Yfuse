set -euo pipefail
test "$GITHUB_ACTIONS" = true
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "$1"
mkdir -p results
collect_evidence() {
  adb logcat -d -b crash > results/crash.txt || true
  adb logcat -d -v threadtime > results/logcat.txt || true
  adb exec-out "run-as com.yfuse.validation sh -c 'cat no_backup/diagnostics/diagnostic-*.jsonl'" > results/app-diagnostics.txt || true
  adb pull /sdcard/Android/data/com.yfuse.validation/files/. results/device-evidence/ || true
}
trap collect_evidence EXIT
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
adb install -r -g preview/app.apk
adb install -r -g preview/tests.apk
adb logcat -c
test_args=(-e notAnnotation com.yfuse.core2.android.ExternalMediaFixture)
if [[ -n "${QA_TEST_CLASSES:-}" ]]; then test_args+=(-e class "$QA_TEST_CLASSES"); fi
adb shell am instrument -w -r "${test_args[@]}" com.yfuse.validation.test/androidx.test.runner.AndroidJUnitRunner | tee results/instrumentation.txt
python3 qa-integrated/check-instrumentation.py results/instrumentation.txt "${QA_MIN_TESTS:-20}"
