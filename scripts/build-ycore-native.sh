#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -gt 0 && "$*" != "--arch arm64" ]]; then
  echo "Usage: build-ycore-native.sh [--arch arm64]" >&2
  exit 2
fi
python3 "$ROOT/scripts/package-ycore-native-aar.py" --self-test
bash "$ROOT/scripts/test-ycore-tone-map.sh"
bash "$ROOT/scripts/test-ycore-disc-uri.sh"
bash "$ROOT/scripts/test-ycore-extradata-budget.sh"
bash "$ROOT/scripts/test-ycore-demux-interrupt.sh"
bash "$ROOT/scripts/test-ycore-demux-seek.sh"
bash "$ROOT/scripts/test-ycore-gpu-capability.sh"
python3 "$ROOT/scripts/test-ycore-shader-calibration.py"
bash "$ROOT/scripts/build-ycore-dependencies.sh"
bash "$ROOT/scripts/build-ycore-demux.sh"
