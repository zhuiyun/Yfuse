#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACTS="${1:-$ROOT/.native-build/artifacts}"
LIBS="$ROOT/composeApp/libs"
AAR="$ARTIFACTS/ycore-native.aar"
CHECKSUM="$AAR.sha256"
SOURCES="$ARTIFACTS/NATIVE-SOURCES.txt"

bash "$ROOT/scripts/verify-ycore-native.sh" "$AAR" "$CHECKSUM" "$SOURCES"
mkdir -p "$LIBS"
install -m 0644 "$AAR" "$LIBS/ycore-native.aar"
install -m 0644 "$CHECKSUM" "$LIBS/ycore-native.aar.sha256"
install -m 0644 "$SOURCES" "$LIBS/ycore-native.sources.txt"

echo "installed verified standalone YCore runtime in $LIBS"
echo "build with: ./gradlew :composeApp:assembleDebug -PyfuseNativeOnlyRuntime=true"
