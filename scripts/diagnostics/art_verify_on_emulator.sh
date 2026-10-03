#!/usr/bin/env bash
# Installs each APK on the running emulator, makes ART verify every class of it from scratch
# (compiler filter "verify", previous dexopt output deleted), and prints what dex2oat logged about
# classes it rejected. A rejected class is the VerifyError a device throws the first time that
# class is used.
#
# usage: art_verify_on_emulator.sh <output-dir> <apk>...
set -uo pipefail

out="$1"
shift
mkdir -p "$out"
adb wait-for-device
echo "Emulator API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), ART module:"
adb shell pm list packages --apex-only --show-versioncode 2>/dev/null | grep -i '\.art' || true

for apk in "$@"; do
  name="$(basename "$apk" .apk)"
  adb uninstall com.yfuse >/dev/null 2>&1 || true
  # Install-time dexopt verifies the classes once; later runs reuse that vdex, so the log is
  # cleared before the install and the dexopt output deleted before the forced pass.
  adb logcat -c || true
  if ! adb install -r "$apk" >"$out/$name-install.txt" 2>&1; then
    echo "::warning::$name did not install"
    cat "$out/$name-install.txt"
    continue
  fi
  adb shell pm delete-dexopt com.yfuse 2>&1 | tee "$out/$name-delete-dexopt.txt"
  adb shell cmd package compile -m verify -f com.yfuse 2>&1 | tee "$out/$name-compile.txt"
  sleep 10
  adb logcat -d -v threadtime >"$out/$name-logcat.txt" 2>&1 || true
  echo "== $name: verifier messages"
  grep -E 'Verification error|Verifier rejected|failed to verify|VerifyError|hard failure|PlayerRootKt' \
    "$out/$name-logcat.txt" | cut -c1-600 | head -n 40 || echo "(none)"
  echo "== $name: dex2oat runs"
  grep -E 'dex2oat took|Running dex2oat|Dexopt result' "$out/$name-logcat.txt" | cut -c1-260 || true
done
