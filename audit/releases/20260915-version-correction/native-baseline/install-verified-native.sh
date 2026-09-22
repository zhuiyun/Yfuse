#!/usr/bin/env bash
# Run the repository's unchanged native gates with existing Windows LLVM/JDK tools.
set -euo pipefail
# Git Bash supports CRLF checkouts without changing the checked-in gate scripts.
set -o igncr
export SHELLOPTS
ROOT=/d/Demo/Yfuse
export YFUSE_NATIVE_AUDIT="$ROOT/audit/releases/20260915-version-correction/native-baseline"
export YFUSE_NATIVE_LIBS="$ROOT/composeApp/libs"
export TMPDIR="$YFUSE_NATIVE_AUDIT/tmp"
export PATH="/usr/bin:/c/Users/app-inkbird/.jdks/ms-21.0.9/bin:$PATH"
mkdir -p "$TMPDIR"
[[ "$(realpath "$YFUSE_NATIVE_LIBS")" == /d/Demo/Yfuse/composeApp/libs ]]

readelf() { /d/AndroidSDK/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe "$@"; }
strings() { /d/AndroidSDK/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe "$@"; }
python3() { /c/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe "$@"; }

# Existing gate scripts clean their mktemp directories in EXIT traps. Check every resolved
# cleanup target before allowing removal; all temporary files stay in this task's audit area.
rm() {
    local argument resolved
    for argument in "$@"; do
        [[ "$argument" == -* ]] && continue
        resolved="$(realpath -m -- "$argument")"
        case "$resolved" in
            "$YFUSE_NATIVE_AUDIT/tmp/"*|"$YFUSE_NATIVE_LIBS/.libmpv-release."*) ;;
            *) printf 'Refusing cleanup outside the verified temporary paths: %s\n' "$resolved" >&2; return 1 ;;
        esac
    done
    command rm "$@"
}
export -f readelf strings python3 rm
cd "$ROOT"
ARTIFACTS="$YFUSE_NATIVE_AUDIT/extracted"
bash scripts/install-yfuse-mpv-bluray.sh "$ARTIFACTS/libmpv-yfuse-bluray.aar" \
    "$ARTIFACTS/libmpv-yfuse-bluray.aar.sha256" "$ARTIFACTS/NATIVE-SOURCES.txt"
bash scripts/install-ycore-native.sh "$ARTIFACTS"
