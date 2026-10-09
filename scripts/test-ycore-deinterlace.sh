#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/native-test-build.sh"
ycore_native_test ycore-deinterlace-test "$ROOT/scripts/native/ycore_deinterlace_test.cpp"
echo "[ycore-deinterlace] tests passed"
