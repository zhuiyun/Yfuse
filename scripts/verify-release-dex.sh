#!/usr/bin/env bash
# Rejects a release APK whose DEX code ART's verifier would refuse to load.
#
# A class with one such method throws VerifyError the first time it is used, on every device, and
# nothing before that notices: R8 builds the APK, unit tests run on the JVM and the app starts,
# but the screen that uses the class crashes. 1.0.97 (259) shipped that way: R8 9.1.31 emitted
# PlayerRootKt.PlayerRoot$lambda$152 with an object where an int belongs, and the player crashed
# as it opened. See scripts/dex-verify/DexRegisterTypeCheck.java for what is checked.
#
# usage: scripts/verify-release-dex.sh [--list-registers-over N] [--mapping <R8 mapping.txt>] <apk-or-dex>...
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${DEX_VERIFY_TOOLS_DIR:-$ROOT/build/dex-verify-tools}"
MAVEN_CENTRAL="https://repo1.maven.org/maven2"
# The checker's jars and their SHA-256; scripts/build-release-packages.ps1 reads the same list.
CHECKSUMS="$ROOT/scripts/dex-verify/tools.sha256"

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

(( $# > 0 )) || die "usage: $0 [--list-registers-over N] [--mapping <mapping.txt>] <apk-or-dex>..."
command -v java >/dev/null 2>&1 || die "java (11 or newer) is required"
mkdir -p "$TOOLS"

classpath=""
while read -r expected path; do
  [[ -z "$expected" || "$expected" == \#* ]] && continue
  jar="$TOOLS/${path##*/}"
  if [[ ! -f "$jar" || "$(sha256_of "$jar")" != "$expected" ]]; then
    curl -fsSL --retry 3 --proto '=https' -o "$jar.part" "$MAVEN_CENTRAL/$path" </dev/null
    actual="$(sha256_of "$jar.part")"
    if [[ "$actual" != "$expected" ]]; then
      rm -f "$jar.part"
      die "$path has SHA-256 $actual, expected $expected"
    fi
    mv "$jar.part" "$jar"
  fi
  classpath+="${classpath:+:}$jar"
done <"$CHECKSUMS"
[[ -n "$classpath" ]] || die "no tool jars listed in $CHECKSUMS"

exec java -Xmx2g -cp "$classpath" "$ROOT/scripts/dex-verify/DexRegisterTypeCheck.java" "$@"
