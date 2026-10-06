#!/usr/bin/env bash
# Strips a native library for shipping while keeping two ways back to its source:
#
#  - the unstripped copy, written to SYMBOL_COPY, which crash reports reach through the library's
#    GNU build id (it carries full DWARF when the library was built with -g);
#  - a .gnu_debugdata section: an xz-compressed ELF holding the function symbol table. Android's
#    unwinder reads it to put function names into on-device tombstones, which is what the app's
#    native crash monitor reports. AOSP ships its own libraries this way, and the section survives
#    the --strip-unneeded that AGP applies again when it packages the APK.
#
# Usage: embed-mini-debuginfo.sh LLVM_BIN_DIR LIBRARY SYMBOL_COPY
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 LLVM_BIN_DIR LIBRARY SYMBOL_COPY" >&2
  exit 2
fi
LLVM_BIN="$1"
LIBRARY="$2"
SYMBOL_COPY="$3"

fail() {
  echo "[mini-debuginfo] $*" >&2
  exit 1
}

for tool in llvm-objcopy llvm-nm llvm-strip llvm-readelf; do
  [[ -x "$LLVM_BIN/$tool" ]] || fail "missing $LLVM_BIN/$tool"
done
command -v xz >/dev/null 2>&1 || fail "xz is required"
[[ -f "$LIBRARY" ]] || fail "missing library $LIBRARY"
"$LLVM_BIN/llvm-readelf" -n "$LIBRARY" | grep -q "Build ID" ||
  fail "$(basename "$LIBRARY") has no GNU build id to match crash reports against"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$(dirname "$SYMBOL_COPY")"
cp -f "$LIBRARY" "$SYMBOL_COPY"

# Function symbols that are not already dynamic: exported ones resolve through .dynsym anyway.
"$LLVM_BIN/llvm-nm" -D --format=posix --defined-only "$LIBRARY" 2>/dev/null |
  awk '{ print $1 }' | sort -u > "$WORK/dynamic-symbols"
"$LLVM_BIN/llvm-nm" --format=posix --defined-only "$LIBRARY" |
  awk '$2 == "T" || $2 == "t" || $2 == "W" || $2 == "w" { print $1 }' | sort -u > "$WORK/function-symbols"
comm -13 "$WORK/dynamic-symbols" "$WORK/function-symbols" > "$WORK/keep-symbols"
# --keep-symbols with an empty list would keep every symbol.
echo >> "$WORK/keep-symbols"

"$LLVM_BIN/llvm-objcopy" --only-keep-debug "$LIBRARY" "$WORK/debug"
"$LLVM_BIN/llvm-objcopy" -S --remove-section .gdb_index --remove-section .comment \
  --keep-symbols="$WORK/keep-symbols" "$WORK/debug" "$WORK/mini-debuginfo"
xz --block-size=64k --threads=0 --stdout "$WORK/mini-debuginfo" > "$WORK/mini-debuginfo.xz"

"$LLVM_BIN/llvm-strip" --strip-unneeded "$LIBRARY"
"$LLVM_BIN/llvm-objcopy" --remove-section .gnu_debugdata "$LIBRARY"
"$LLVM_BIN/llvm-objcopy" --add-section .gnu_debugdata="$WORK/mini-debuginfo.xz" "$LIBRARY"

echo "[mini-debuginfo] $(basename "$LIBRARY"): $(wc -l < "$WORK/keep-symbols") function names, symbols kept at $SYMBOL_COPY"
