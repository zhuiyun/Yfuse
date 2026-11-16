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
if [[ -n "${QA_PLATFORM_AVC_DECODER:-}" ]]; then
  printf 'Explicit platform-only fixture decoder: %s\n' "$QA_PLATFORM_AVC_DECODER" | tee results/platform-decoder.txt
  test_args+=(-e platformAvcDecoder "$QA_PLATFORM_AVC_DECODER")
fi
adb shell am instrument -w -r "${test_args[@]}" com.yfuse.validation.test/androidx.test.runner.AndroidJUnitRunner | tee results/instrumentation.txt
python3 qa-integrated/check-instrumentation.py results/instrumentation.txt "${QA_MIN_TESTS:-20}"
if [[ "${QA_REPEAT_UI_TIMING:-false}" == true ]]; then
  timing_cases='com.yfuse.feature.library.LibraryHomeLifecycleInstrumentedTest#arriving_libraries_start_at_first_card_and_later_refresh_keeps_user_scroll,com.yfuse.feature.player.SoftFeedbackInstrumentedTest#compound_play_surface_preserves_separate_actions_and_disabled_state'
  for iteration in $(seq 1 10); do
    adb shell am instrument -w -r -e class "$timing_cases" com.yfuse.validation.test/androidx.test.runner.AndroidJUnitRunner | tee "results/timing-repeat-$iteration.txt"
    python3 qa-integrated/check-instrumentation.py "results/timing-repeat-$iteration.txt" 2
  done
fi
