#!/usr/bin/env bash
# Installs a verified current native carrier into the Full application's dependency layout.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_ARTIFACTS="$ROOT/.native-build/artifacts"
AAR="${1:-$DEFAULT_ARTIFACTS/libmpv-yfuse-bluray.aar}"
SHA_FILE="${2:-$AAR.sha256}"
SOURCES="${3:-$DEFAULT_ARTIFACTS/NATIVE-SOURCES.txt}"
LIBS="$ROOT/composeApp/libs"
DEST="$LIBS/libmpv-release.aar"
DEST_SHA="$LIBS/libmpv-release.aar.sha256"
DEST_SOURCES="$LIBS/libmpv-release.sources.txt"
GPU_AAR="$(dirname "$AAR")/ycore-gpu.aar"

# This is the Full compatibility carrier, not the standalone ycore-native.aar. The standalone
# verifier intentionally rejects libmpv/libplayer and requires its own different embedded manifest.
# Preserve all checksum, dependency, alignment, optical-disc and optional Dolby carrier gates.
bash "$ROOT/scripts/verify-yfuse-mpv-bluray-aar.sh" "$AAR" "$SHA_FILE" "$SOURCES"
if grep -Fx 'dolby-vision-rpu=true' "$SOURCES" >/dev/null &&
  grep -Fx 'dolby-vision-fel=true' "$SOURCES" >/dev/null; then
  bash "$ROOT/scripts/verify-yfuse-mpv-dolby-aar.sh" "$AAR" "$SHA_FILE" "$SOURCES"
fi

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print tolower($1) }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print tolower($1) }'
  else
    printf 'error: sha256sum or shasum is required\n' >&2
    exit 1
  fi
}

mkdir -p "$LIBS"
tmp_aar="$(mktemp "$LIBS/.libmpv-release.aar.XXXXXX")"
tmp_sha="$(mktemp "$LIBS/.libmpv-release.sha256.XXXXXX")"
tmp_sources="$(mktemp "$LIBS/.libmpv-release.sources.XXXXXX")"
trap 'rm -f "${tmp_aar:-}" "${tmp_sha:-}" "${tmp_sources:-}"' EXIT

# A freshly compiled carrier contains the GPU library, unlike the older pinned release.
# Full builds also depend on ycore-gpu.aar to advertise the GPU capability. Do not hide duplicate
# or conflicting native libraries with pickFirst: verify equality and package the GPU once.
if [[ -f "$GPU_AAR" ]]; then
  python3 - "$AAR" "$GPU_AAR" "$tmp_aar" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

carrier, companion, destination = map(pathlib.Path, sys.argv[1:])
sidecar = pathlib.Path(str(companion) + '.sha256')
if not sidecar.is_file():
    raise SystemExit('GPU companion checksum is missing')
expected = sidecar.read_text().split()[0].lower()
if hashlib.sha256(companion.read_bytes()).hexdigest() != expected:
    raise SystemExit('GPU companion checksum mismatch')
with zipfile.ZipFile(carrier) as source, zipfile.ZipFile(companion) as gpu:
    entries = [name for name in source.namelist() if name.startswith('jni/') and name.endswith('/libycore_gpu.so')]
    if not entries:
        raise SystemExit('Current carrier has no GPU library to split')
    for name in entries:
        if name not in gpu.namelist() or source.read(name) != gpu.read(name):
            raise SystemExit('Carrier and GPU companion differ: ' + name)
    with zipfile.ZipFile(destination, 'w') as output:
        for entry in source.infolist():
            if entry.filename not in entries:
                output.writestr(entry, source.read(entry.filename))
PY
  install -m 0644 "$GPU_AAR" "$LIBS/ycore-gpu.aar"
  install -m 0644 "$GPU_AAR.sha256" "$LIBS/ycore-gpu.aar.sha256"
  install -m 0644 "$SOURCES" "$LIBS/ycore-gpu.sources.txt"
else
  cp "$AAR" "$tmp_aar"
fi

actual_sha="$(sha256_of "$tmp_aar")"
printf '%s  libmpv-release.aar\n' "$actual_sha" >"$tmp_sha"
cp "$SOURCES" "$tmp_sources"

# Sidecars first, AAR last. Runtime capability still comes from the actual packaged libraries.
mv -f "$tmp_sha" "$DEST_SHA"
mv -f "$tmp_sources" "$DEST_SOURCES"
mv -f "$tmp_aar" "$DEST"
trap - EXIT

printf 'installed: %s\n' "$DEST"
printf 'sha256:   %s\n' "$actual_sha"
if grep -Fx 'dolby-vision-rpu=true' "$SOURCES" >/dev/null &&
  grep -Fx 'dolby-vision-fel=true' "$SOURCES" >/dev/null; then
  printf 'capability: native Blu-ray + YCore demux + Dolby Vision RPU/FEL gpu-next; BD-J disabled\n'
else
  printf 'capability: native Blu-ray ISO + BDMV VFS + HDMV menu + YCore demux; BD-J disabled\n'
fi
