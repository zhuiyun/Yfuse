# YCore media device suite

The repository contains the runner and a coverage-complete manifest template, but not copyrighted
or Dolby-licensed media. Populate a private corpus using `ycore-suite.example.json` as the contract.

Push the directory into the app-specific external files area, then run the connected-device test:

```powershell
adb push .\private-ycore-suite\ /sdcard/Android/data/com.yfuse/files/ycore-suite/
.\gradlew.bat :composeApp:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.yfuse.core2.android.YCoreMediaSuiteInstrumentedTest `
  -Pandroid.testInstrumentationRunnerArguments.ycoreMediaManifest=/sdcard/Android/data/com.yfuse/files/ycore-suite/ycore-suite.json
```

The manifest validator rejects missing matrix dimensions, duplicate IDs, unsafe paths, and missing
lifecycle operations. The device runner fails on missing files, backend failure, first-frame timeout,
seek/resume failure, Surface recreation failure, or next-item failure. A missing manifest skips the
licensed-corpus lane rather than reporting a false pass.

For a fast baseline check that does not claim matrix coverage, push any ordinary unprotected MP4
into the app-specific directory and run only the smoke method:

```powershell
adb push .\baseline.mp4 /sdcard/Android/data/com.yfuse/files/ycore-smoke/baseline.mp4
adb shell am instrument -w `
  -e class com.yfuse.core2.android.YCoreMediaSuiteInstrumentedTest#baseline_media_survives_core_playback_lifecycle `
  -e ycoreSmokeMedia /sdcard/Android/data/com.yfuse/files/ycore-smoke/baseline.mp4 `
  com.yfuse.test/androidx.test.runner.AndroidJUnitRunner
```

The smoke lane requires a natively executable Core2 route. By default it verifies 100 distinct
seek/reset/new-frame cycles, pause/resume, eight alternating landscape/portrait Surface
recreations, next/previous item round-trip, and release. It prints progress every five seeks.

For an unstable USB connection, split the same deterministic 100-target sequence into shorter
runs. `ycoreSeekStart` is zero-based; the example below verifies targets 41 through 50 and skips
Surface recreation for that segment:

```powershell
adb shell am instrument -r -w `
  -e class com.yfuse.core2.android.YCoreMediaSuiteInstrumentedTest#baseline_media_survives_core_playback_lifecycle `
  -e ycoreSmokeMedia /sdcard/Android/data/com.yfuse/files/ycore-smoke/baseline.mp4 `
  -e ycoreSeekStart 40 `
  -e ycoreSeekIterations 10 `
  -e ycoreSurfaceIterations 0 `
  com.yfuse.test/androidx.test.runner.AndroidJUnitRunner
```

The smoke lane does not substitute for the licensed Dolby Vision, lossless/immersive audio,
subtitle, ISO, high-bitrate, thermal, or power corpus.

## Isolated validation package

If a differently signed `com.yfuse` build is already installed, build the test pair with a
temporary application id instead of replacing it:

```powershell
.\gradlew.bat --max-workers=1 :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest `
  "-PyfuseApplicationId=com.yfuse.validation"
adb install -r -t .\composeApp\build\outputs\apk\debug\composeApp-debug.apk
adb install -r -t .\composeApp\build\outputs\apk\androidTest\debug\composeApp-debug-androidTest.apk
```

Use `com.yfuse.validation` in the device media path and
`com.yfuse.validation.test/androidx.test.runner.AndroidJUnitRunner` as the instrumentation target.
The property only affects builds that explicitly supply it; normal builds remain `com.yfuse`.
