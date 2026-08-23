#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$ROOT/scripts/build-yfuse-mpv-dolby.sh" "$@"
bash "$ROOT/scripts/build-ycore-demux.sh"
