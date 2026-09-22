#!/usr/bin/env bash
# Builds the existing Yfuse optical-disc mpv variant on a current Dolby Vision RPU/FEL stack.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="$ROOT/scripts/build-yfuse-mpv-bluray.sh"
PATCHER="$ROOT/scripts/native/patch_yfuse_mpv_dolby_build.py"

[[ -f "$BASE" ]] || {
  echo "error: missing optical native builder: $BASE" >&2
  exit 1
}
[[ -f "$PATCHER" ]] || {
  echo "error: missing Dolby builder transformer: $PATCHER" >&2
  exit 1
}

# The base script derives ROOT from its own location. Keep the generated copy beside it rather than
# in /tmp so all existing relative source and artifact paths remain identical.
GENERATED="$(mktemp "$ROOT/scripts/.build-yfuse-mpv-dolby.XXXXXX.sh")"
cleanup() {
  rm -f "$GENERATED"
}
trap cleanup EXIT

cp "$BASE" "$GENERATED"
python3 "$PATCHER" "$GENERATED"
chmod +x "$GENERATED"

"$GENERATED" "$@"
