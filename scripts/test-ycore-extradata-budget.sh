#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/native-test-build.sh
source "$ROOT/scripts/native-test-build.sh"

ycore_native_test ycore-extradata-budget-test "$ROOT/scripts/native/ycore_extradata_budget_test.cpp"
echo "[ycore-extradata-budget] tests passed"
