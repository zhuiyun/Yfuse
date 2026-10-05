#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CXX="${CXX:-c++}"
OUTPUT="$(mktemp "${TMPDIR:-/tmp}/ycore-tone-map.XXXXXX")"
trap 'rm -f "$OUTPUT"' EXIT

# -DNDEBUG matches the release flags; the test's checks do not depend on assert().
"$CXX" \
  -std=c++17 \
  -O2 \
  -DNDEBUG \
  -pthread \
  -Wall \
  -Wextra \
  -Werror \
  "$ROOT/scripts/native/ycore_tone_map_test.cpp" \
  -o "$OUTPUT"
"$OUTPUT"

echo "[ycore-tone-map] tests passed"
