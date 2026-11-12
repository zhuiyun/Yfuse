set -euo pipefail
mkdir -p navigation-evidence
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
adb install -r -g "$(find app/composeApp/build/outputs/apk/debug -name '*.apk' | head -1)"
adb install -r -g "$(find app/composeApp/build/outputs/apk/androidTest -name '*.apk' | head -1)"
for repeat in 1 2 3 4; do
  adb shell am instrument -w -e class com.yfuse.feature.personal.PersonalSearchNavigationInstrumentedTest,com.yfuse.feature.player.PlayerDragReleaseInstrumentedTest com.yfuse.validation.test/androidx.test.runner.AndroidJUnitRunner | tee "navigation-evidence/pass-$repeat.txt"
  grep -Fq 'OK (2 tests)' "navigation-evidence/pass-$repeat.txt"
done
