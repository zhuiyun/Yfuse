#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT/scripts/build-yfuse-mpv-bluray.sh" "$@"
"$ROOT/scripts/build-ycore-demux.sh"
