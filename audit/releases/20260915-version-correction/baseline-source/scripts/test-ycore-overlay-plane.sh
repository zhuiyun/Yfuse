#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CXX="${CXX:-c++}"
OUTPUT="$(mktemp "${TMPDIR:-/tmp}/ycore-overlay-plane.XXXXXX")"
trap 'rm -f "$OUTPUT"' EXIT

"$CXX" \
  -std=c++17 \
  -O2 \
  -Wall \
  -Wextra \
  -Werror \
  "$ROOT/scripts/native/ycore_overlay_plane_test.cpp" \
  -o "$OUTPUT"
"$OUTPUT"

echo "[ycore-overlay-plane] tests passed"
