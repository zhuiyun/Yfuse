#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACTS="${1:-$ROOT/.native-build/artifacts}"
LIBS="$ROOT/composeApp/libs"
AAR="$ARTIFACTS/ycore-native.aar"
CHECKSUM="$AAR.sha256"
SOURCES="$ARTIFACTS/NATIVE-SOURCES.txt"
GPU_AAR="$ARTIFACTS/ycore-gpu.aar"
GPU_CHECKSUM="$GPU_AAR.sha256"

bash "$ROOT/scripts/verify-ycore-native.sh" "$AAR" "$CHECKSUM" "$SOURCES"
mkdir -p "$LIBS"
install -m 0644 "$AAR" "$LIBS/ycore-native.aar"
install -m 0644 "$CHECKSUM" "$LIBS/ycore-native.aar.sha256"
install -m 0644 "$SOURCES" "$LIBS/ycore-native.sources.txt"
install -m 0644 "$GPU_AAR" "$LIBS/ycore-gpu.aar"
install -m 0644 "$GPU_CHECKSUM" "$LIBS/ycore-gpu.aar.sha256"
install -m 0644 "$SOURCES" "$LIBS/ycore-gpu.sources.txt"

echo "installed verified standalone YCore runtime in $LIBS"
echo "installed verified full-app YCore GPU companion in $LIBS"
echo "native-only build: ./gradlew :composeApp:assembleDebug -PyfuseNativeOnlyRuntime=true"
echo "full build: ./gradlew :composeApp:assembleDebug"
