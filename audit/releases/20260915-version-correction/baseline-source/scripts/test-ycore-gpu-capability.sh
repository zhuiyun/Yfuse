#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CXX="${CXX:-c++}"
OUTPUT="$(mktemp "${TMPDIR:-/tmp}/ycore-gpu-capability.XXXXXX")"
trap 'rm -f "$OUTPUT"' EXIT

"$CXX" \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Wpedantic \
  -Werror \
  -I"$ROOT/scripts/native" \
  "$ROOT/scripts/native/ycore_gpu_capability_test.cpp" \
  -o "$OUTPUT"
"$OUTPUT"
echo "YCore GPU capability gate verified"
