#!/usr/bin/env bash
# Historical entry point retained for callers; standard preparation is exclusively YCore + SenPlayer.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"${PYTHON:-python3}" "$ROOT/scripts/prepare-playback-inputs.py"
