#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/native-test-build.sh
source "$ROOT/scripts/native-test-build.sh"

ycore_native_test ycore-disc-uri-test -I"$ROOT/scripts/native" "$ROOT/scripts/native/ycore_disc_uri_test.cpp"
echo "[ycore-disc-uri] tests passed"

# The Blu-ray stream language lookup.
ycore_native_test ycore-disc-language-test \
  -I"$ROOT/scripts/native" "$ROOT/scripts/native/ycore_disc_language_test.cpp"
echo "[ycore-disc-language] tests passed"
