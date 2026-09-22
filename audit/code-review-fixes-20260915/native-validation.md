# Native ABI regression validation

Date: 2026-09-15. Workspace: `D:\Demo\Yfuse`.

This records commands actually executed during the code-review fixes. The final source and
test changes were compiled and executed successfully. No APK was built or installed. These
tests exercise the portable `ycore-native` coordinator with fake backends; they do not claim
that the current Android APK uses this ABI or validate real decoder/HDR output.

## Compiler and target

- Compiler: existing Android NDK `29.0.14206865` Clang.
- Target: `aarch64-linux-android26`, C++17, statically linked C++ runtime.
- Warnings: `-Wall -Wextra -Wpedantic -Werror`.
- Device reported by `adb devices`: `RF8M223V4MD`, state `device`.
- Local test artifact: `D:\Demo\Yfuse\tmp\code-review-20260915\ycore-regression-test`.

An initial `-fsyntax-only` check of all three translation units passed. The final regression
binary was then compiled with this PowerShell command from the workspace root:

```powershell
$testBinary = Join-Path (Get-Location) 'tmp/code-review-20260915/ycore-regression-test'
& 'D:/AndroidSDK/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe' `
    --target=aarch64-linux-android26 `
    -std=c++17 -Wall -Wextra -Wpedantic -Werror -static-libstdc++ `
    -Iycore-native/include `
    ycore-native/src/ycore.cpp `
    ycore-native/src/ycore_build.cpp `
    ycore-native/tests/ycore_test.cpp `
    -o $testBinary
```

Compilation exited with code **0**, with no compiler diagnostics. The final executable was
2,895,792 bytes, as reported by `adb push`.

## Execution and cleanup

The following commands were executed sequentially. Upload and permission failures were
checked before running the next command; the test exit code was saved before cleanup.

```powershell
& 'D:/AndroidSDK/platform-tools/adb.exe' -s RF8M223V4MD push `
    $testBinary /data/local/tmp/yfuse-code-review-20260915-ycore-test
& 'D:/AndroidSDK/platform-tools/adb.exe' -s RF8M223V4MD shell chmod 700 `
    /data/local/tmp/yfuse-code-review-20260915-ycore-test
& 'D:/AndroidSDK/platform-tools/adb.exe' -s RF8M223V4MD shell `
    /data/local/tmp/yfuse-code-review-20260915-ycore-test
$testExit = $LASTEXITCODE
& 'D:/AndroidSDK/platform-tools/adb.exe' -s RF8M223V4MD shell rm `
    /data/local/tmp/yfuse-code-review-20260915-ycore-test
Write-Output "Native regression exit=$testExit"
exit $testExit
```

Observed final output:

```text
D:\Demo\Yfuse\tmp\code-review-20260915\ycore-regression-test: 1 file pushed, 0 skipped. 31.1 MB/s (2895792 bytes in 0.089s)
Native regression exit=0
```

The test executable produced no assertion failures. The device temporary executable was
removed by the final `adb shell rm` command, which produced no error. The local test binary
remains in the ignored workspace `tmp` directory. No application data or device settings
were changed.

## Test cases

`ycore-native/tests/ycore_test.cpp::main()` executed all **15 test functions** below. Assertions
were enabled; the compile command did not define `NDEBUG`. The initialization-failure test
also checks four separate failure paths (play, pause, video output, and speed).

1. `test_priority_and_capability_routing`
2. `test_handover_preserves_state`
3. `test_authorization_failure_does_not_change_backend`
4. `test_ffi_convenience_api`
5. `test_build_capabilities_do_not_claim_uncompiled_features`
6. `test_open_failure_falls_back`
7. `test_no_compatible_engine_is_explicit`
8. `test_handover_restores_tracks_output_and_pause_intent`
9. `test_handover_never_reopens_an_engine_this_request_exhausted`
10. `test_drm_failure_does_not_change_backend`
11. `test_initialization_failures_do_not_publish_ready`
12. `test_initialization_failure_tries_the_next_backend`
13. `test_track_restore_failure_is_reported`
14. `test_new_media_clears_tracks_and_output_evidence`
15. `test_rejected_speed_does_not_replace_the_last_accepted_value`

The five new functions verify that failed initialization never publishes READY, fallback
continues after initialization failure, failed track restoration is surfaced, a new media
request clears old media state while retaining the host output, and rejected/NaN speeds do
not overwrite the accepted speed. The existing preservation, routing, failure-policy, and
FFI tests continued to pass.
