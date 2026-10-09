#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/native-test-build.sh
source "$ROOT/scripts/native-test-build.sh"

ycore_native_test ycore-demux-interrupt-test "$ROOT/scripts/native/ycore_demux_interrupt_test.cpp"
echo "[ycore-demux-interrupt] tests passed"
