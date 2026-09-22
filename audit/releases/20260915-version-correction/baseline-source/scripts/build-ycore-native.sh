#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The pinned compatibility build supplies the reproducible Android FFmpeg/libbluray toolchain.
# build-ycore-demux.sh then compiles YCore and emits a dependency-closed ycore-native.aar that
# contains none of the mpv/player/MDK runtime.
python3 "$ROOT/scripts/package-ycore-native-aar.py" --self-test
bash "$ROOT/scripts/test-ycore-tone-map.sh"
bash "$ROOT/scripts/test-ycore-disc-uri.sh"
bash "$ROOT/scripts/test-ycore-gpu-capability.sh"
python3 "$ROOT/scripts/test-ycore-shader-calibration.py"
bash "$ROOT/scripts/build-yfuse-mpv-dolby.sh" "$@"
bash "$ROOT/scripts/build-ycore-demux.sh"
