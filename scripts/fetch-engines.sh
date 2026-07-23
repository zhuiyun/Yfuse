#!/usr/bin/env bash
# Fetches the native player-engine binaries into composeApp/libs/.
# They are gitignored (≈115 MB) so the repo stays small.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/composeApp/libs"
mkdir -p "$LIBS"

MPV_URL="https://github.com/jarnedemeulemeester/libmpv-android/releases/download/v1.0.0/libmpv-release.aar"
MDK_URL="https://github.com/wang-bin/mdk-sdk/releases/download/v0.37.0/mdk-sdk-android.7z"

echo "==> libmpv"
curl -fL --retry 3 -o "$LIBS/libmpv-release.aar" "$MPV_URL"

echo "==> mdk"
curl -fL --retry 3 -o "$LIBS/mdk-sdk-android.7z" "$MDK_URL"
if command -v 7z >/dev/null 2>&1; then
  (cd "$LIBS" && 7z x -y mdk-sdk-android.7z >/dev/null)
elif [ -x "/c/Program Files/7-Zip/7z.exe" ]; then
  (cd "$LIBS" && "/c/Program Files/7-Zip/7z.exe" x -y mdk-sdk-android.7z >/dev/null)
else
  echo "!! 7z not found — extract mdk-sdk-android.7z manually" >&2
fi

echo "done: $LIBS"
