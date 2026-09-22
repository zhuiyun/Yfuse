$ErrorActionPreference = 'Stop'
$compiler = 'D:/AndroidSDK/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'The configured local NDK 29 compiler is unavailable.' }
$sources = @('scripts/native/ycore_extradata_budget_test.cpp', 'scripts/native/ycore_demux_interrupt_test.cpp')
foreach ($source in $sources) {
    & $compiler --target=aarch64-linux-android26 -std=c++17 -Wall -Wextra -Werror -fsyntax-only $source
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Output "PASS: Android aarch64 syntax check: $source"
}
Write-Output 'Mode: syntax only. No C++ test executable was run locally; host execution requires a C++17 host compiler and Bash. CI/build-ycore-native.sh invokes both corresponding test shell scripts.'
