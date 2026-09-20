#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${YCORE_TEST_BUILD_DIR:-$ROOT/.native-build/extradata-budget-test}"
CXX="${CXX:-c++}"
mkdir -p "$BUILD_DIR"
"$CXX" -std=c++17 -Wall -Wextra -Werror \
  "$ROOT/scripts/native/ycore_extradata_budget_test.cpp" \
  -o "$BUILD_DIR/ycore-extradata-budget-test"
"$BUILD_DIR/ycore-extradata-budget-test"
echo "[ycore-extradata-budget] tests passed"
