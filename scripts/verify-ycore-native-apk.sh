#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point for native CI callers. Standard delivery now contains both
# standalone YCore without other player runtimes.
APK="${1:-}"
[[ -n "$APK" && -f "$APK" ]] || {
  echo "usage: $0 <ycore.apk> [r8-mapping.txt]" >&2
  exit 2
}
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
args=("$APK")
if [[ -n "${2:-}" ]]; then
  args+=(--mapping "$2")
fi
"${PYTHON:-python3}" "$root/scripts/verify-playback-package.py" "${args[@]}"
