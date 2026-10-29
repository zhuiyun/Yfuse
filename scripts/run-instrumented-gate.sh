#!/usr/bin/env bash
# Keep device evidence before android-emulator-runner shuts the emulator down.
set -euo pipefail

expected_api="$1"
shift

collect_evidence() {
  local result=$?
  trap - EXIT
  mkdir -p composeApp/build/outputs/widget-layout-evidence
  adb pull /sdcard/Android/data/com.yfuse.validation/files/. \
    composeApp/build/outputs/widget-layout-evidence/ || true
  exit "$result"
}
trap collect_evidence EXIT

test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "$expected_api"
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
# AGP otherwise uninstalls the isolated test apps and removes their external files
# before the EXIT trap can pull the screenshots from this disposable emulator.
# This CI lane supplies no external corpus. Run every generated-media test; the separately
# selectable licensed-corpus lanes remain available to explicit instrumentation callers.
# AGP's device adapter currently reports assumption-failure status -4 as a test failure.
./gradlew :composeApp:connectedDebugAndroidTest -PyfuseApplicationId=com.yfuse.validation "$@" \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.yfuse.core2.android.ExternalMediaFixture \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon
