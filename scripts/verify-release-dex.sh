#!/usr/bin/env bash
# Rejects a release APK whose DEX code ART's verifier would refuse to load.
#
# A class with one such method throws VerifyError the first time it is used, on every device, and
# nothing before that notices: R8 builds the APK, unit tests run on the JVM and the app starts,
# but the screen that uses the class crashes. 1.0.97 (259) shipped that way: R8 9.1.31 emitted
# PlayerRootKt.PlayerRoot$lambda$152 with an object where an int belongs, and the player crashed
# as it opened. See scripts/dex-verify/DexRegisterTypeCheck.java for what is checked.
#
# usage: scripts/verify-release-dex.sh <apk-or-dex>...
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${DEX_VERIFY_TOOLS_DIR:-$ROOT/build/dex-verify-tools}"
MAVEN_CENTRAL="https://repo1.maven.org/maven2"

# dexlib2 (baksmali's verifier model) and its runtime dependencies: Maven Central path, SHA-256.
JARS=(
  "org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar 5a5c8982d8bd7d6e3bb1a0713049e3c78b719ec32b20f6b619885cec30a0dd61"
  "com/google/guava/guava/27.1-android/guava-27.1-android.jar 686404f2d1d4d221911f96bd627ff60dac2226a5dfa6fb8ba517073eb97ec0ef"
  "com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26"
)

die() {
  printf 'error: %s\n' "$*" >&2
  exit 2
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print tolower($1) }'
  else
    shasum -a 256 "$1" | awk '{ print tolower($1) }'
  fi
}

(( $# > 0 )) || die "usage: $0 <apk-or-dex>..."
command -v java >/dev/null 2>&1 || die "java (11 or newer) is required"
mkdir -p "$TOOLS"

classpath=""
for entry in "${JARS[@]}"; do
  read -r path expected <<<"$entry"
  jar="$TOOLS/${path##*/}"
  if [[ ! -f "$jar" || "$(sha256_of "$jar")" != "$expected" ]]; then
    curl -fsSL --retry 3 --proto '=https' -o "$jar.part" "$MAVEN_CENTRAL/$path"
    actual="$(sha256_of "$jar.part")"
    if [[ "$actual" != "$expected" ]]; then
      rm -f "$jar.part"
      die "$path has SHA-256 $actual, expected $expected"
    fi
    mv "$jar.part" "$jar"
  fi
  classpath+="${classpath:+:}$jar"
done

exec java -Xmx2g -cp "$classpath" "$ROOT/scripts/dex-verify/DexRegisterTypeCheck.java" "$@"
