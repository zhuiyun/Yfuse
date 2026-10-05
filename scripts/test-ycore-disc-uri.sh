#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

"${CXX:-c++}" \
  -std=c++17 \
  -Wall \
  -Wextra \
  -Werror \
  -I"$ROOT/scripts/native" \
  "$ROOT/scripts/native/ycore_disc_uri_test.cpp" \
  -o "$BUILD_DIR/ycore-disc-uri-test"

"$BUILD_DIR/ycore-disc-uri-test"
echo "[ycore-disc-uri] tests passed"

# The Blu-ray stream language lookup, built optimised and with NDEBUG as release code is.
"${CXX:-c++}" \
  -std=c++17 \
  -O2 \
  -DNDEBUG \
  -Wall \
  -Wextra \
  -Werror \
  -I"$ROOT/scripts/native" \
  "$ROOT/scripts/native/ycore_disc_language_test.cpp" \
  -o "$BUILD_DIR/ycore-disc-language-test"

"$BUILD_DIR/ycore-disc-language-test"
echo "[ycore-disc-language] tests passed"
