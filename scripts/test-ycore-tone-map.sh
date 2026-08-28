#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CXX="${CXX:-c++}"
OUTPUT="$(mktemp "${TMPDIR:-/tmp}/ycore-tone-map.XXXXXX")"
trap 'rm -f "$OUTPUT"' EXIT

"$CXX" \
  -std=c++17 \
  -O2 \
  -Wall \
  -Wextra \
  -Werror \
  "$ROOT/scripts/native/ycore_tone_map_test.cpp" \
  -o "$OUTPUT"
"$OUTPUT"

echo "[ycore-tone-map] tests passed"
