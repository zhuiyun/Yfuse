#!/usr/bin/env bash
# Keep device evidence before android-emulator-runner shuts the emulator down.
set -euo pipefail

expected_api="$1"
shift
evidence="composeApp/build/outputs/widget-layout-evidence"
mkdir -p "$evidence"

collect_evidence() {
  local result=$?
  trap - EXIT
  if (( result != 0 )); then
    # Host evidence survives even when the emulator process vanishes before ADB can pull logs.
    {
      free -h
      df -h /
      cat /sys/fs/cgroup/memory.events 2>/dev/null || true
      sudo -n journalctl -k -b --no-pager --grep='Out of memory|oom-kill|Killed process|segfault' -n 40 || true
      adb devices -l
    } > "$evidence/host-on-failure.txt" 2>&1
  fi
  adb pull /sdcard/Android/data/com.yfuse.validation/files/. \
    "$evidence/" || true
  exit "$result"
}
trap collect_evidence EXIT

test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "$expected_api"
probe=(--api "$expected_api" --output "$evidence/emulator-preflight")
if [[ "$expected_api" == 37 ]]; then
  probe+=(--tablet-apk composeApp/build/outputs/apk/debug/composeApp-debug.apk)
fi
python3 scripts/instrumented_emulator.py "${probe[@]}"
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
# AGP otherwise uninstalls the isolated test apps and removes their external files
# before the EXIT trap can pull the screenshots from this disposable emulator.
# This CI lane supplies no external corpus. Run every generated-media test; the separately
# selectable licensed-corpus lanes remain available to explicit instrumentation callers.
# AGP's device adapter currently reports assumption-failure status -4 as a test failure.
./gradlew :composeApp:connectedDebugAndroidTest -PyfuseApplicationId=com.yfuse.validation "$@" \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.yfuse.core2.android.ExternalMediaFixture \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8" --max-workers=2 --no-daemon
