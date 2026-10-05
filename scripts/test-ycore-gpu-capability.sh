#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/native-test-build.sh
source "$ROOT/scripts/native-test-build.sh"

ycore_native_test ycore-gpu-capability-test \
  -Wpedantic -I"$ROOT/scripts/native" "$ROOT/scripts/native/ycore_gpu_capability_test.cpp"
echo "YCore GPU capability gate verified"
