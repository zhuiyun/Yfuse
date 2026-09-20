#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${YCORE_TEST_BUILD_DIR:-$ROOT/.native-build/demux-interrupt-test}"
CXX="${CXX:-c++}"
mkdir -p "$BUILD_DIR"
"$CXX" -std=c++17 -Wall -Wextra -Werror \
  "$ROOT/scripts/native/ycore_demux_interrupt_test.cpp" \
  -o "$BUILD_DIR/ycore-demux-interrupt-test"
"$BUILD_DIR/ycore-demux-interrupt-test"
echo "[ycore-demux-interrupt] tests passed"
