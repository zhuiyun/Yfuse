#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="${YCORE_PROFILE:-}"
APP_APK="${YCORE_APP_APK:-}"
TEST_APK="${YCORE_TEST_APK:-}"
APP_ID="${YCORE_APPLICATION_ID:-com.yfuse.validation}"
COMMIT_SHA="${YCORE_COMMIT_SHA:-}"
ARTIFACT_SHA256="${YCORE_ARTIFACT_SHA256:-}"
OUTPUT="${YCORE_EVIDENCE_OUTPUT:-$ROOT_DIR/build/ycore-evidence/${PROFILE:-unknown}.json}"
DEVICE_SERIAL="${YCORE_DEVICE_SERIAL:-}"
CORPUS_DIR="${YCORE_CORPUS_DIR:-}"
SOAK_MEDIA="${YCORE_SOAK_MEDIA:-}"

usage() {
  cat >&2 <<'EOF'
Run one YCore physical-device release profile and emit redacted JSON evidence.

Required environment:
  YCORE_PROFILE              matrix | stress | continuous_soak | queue_soak
  YCORE_APP_APK              native-only application APK
  YCORE_TEST_APK             matching androidTest APK
  YCORE_COMMIT_SHA           40-character source commit SHA
  YCORE_ARTIFACT_SHA256      SHA-256 of the tested ycore-native AAR

Profile media:
  matrix:                    YCORE_CORPUS_DIR containing ycore-suite.json
  all other profiles:        YCORE_SOAK_MEDIA pointing to a local media file

Optional:
  YCORE_DEVICE_SERIAL        adb serial (required when more than one device is attached)
  YCORE_APPLICATION_ID       defaults to com.yfuse.validation
  YCORE_EVIDENCE_OUTPUT      output JSON path
EOF
  exit 2
}

[[ "$PROFILE" =~ ^(matrix|stress|continuous_soak|queue_soak)$ ]] || usage
[[ -f "$APP_APK" && -f "$TEST_APK" ]] || usage
[[ "$COMMIT_SHA" =~ ^[0-9a-fA-F]{40}$ ]] || usage
[[ "$ARTIFACT_SHA256" =~ ^[0-9a-fA-F]{64}$ ]] || usage
if [[ "$PROFILE" == "matrix" ]]; then
  [[ -d "$CORPUS_DIR" && -f "$CORPUS_DIR/ycore-suite.json" ]] || usage
else
  [[ -f "$SOAK_MEDIA" ]] || usage
fi

command -v adb >/dev/null || { echo "error: adb is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "error: python3 is required" >&2; exit 2; }

adb_command=(adb)
if [[ -n "$DEVICE_SERIAL" ]]; then
  adb_command+=(-s "$DEVICE_SERIAL")
else
  DEVICE_SERIAL="$(adb get-serialno)"
fi
[[ -n "$DEVICE_SERIAL" && "$DEVICE_SERIAL" != "unknown" ]] || {
  echo "error: no unique adb device is selected" >&2
  exit 2
}

get_property() {
  "${adb_command[@]}" shell getprop "$1" | tr -d '\r' | head -n 1
}

hash_file() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

if [[ "$DEVICE_SERIAL" == emulator-* ]] || \
  [[ "$(get_property ro.kernel.qemu)" == "1" ]] || \
  [[ "$(get_property ro.boot.qemu)" == "1" ]]; then
  echo "error: release evidence must come from a physical Android device" >&2
  exit 1
fi

"$ROOT_DIR/scripts/verify-ycore-native-apk.sh" "$APP_APK"
APK_SHA256="$(hash_file "$APP_APK")"
TEST_APK_SHA256="$(hash_file "$TEST_APK")"
"${adb_command[@]}" install -r -t "$APP_APK"
"${adb_command[@]}" install -r -t "$TEST_APK"

remote_root="/sdcard/Android/data/$APP_ID/files/ycore-gates/$PROFILE"
"${adb_command[@]}" shell mkdir -p "$remote_root"
test_method=""
instrument_arguments=()
case "$PROFILE" in
  matrix)
    "${adb_command[@]}" push "$CORPUS_DIR/." "$remote_root/"
    test_method="full_media_matrix_survives_core_playback_lifecycle"
    instrument_arguments+=(-e ycoreMediaManifest "$remote_root/ycore-suite.json")
    ;;
  stress)
    media_extension="${SOAK_MEDIA##*.}"
    [[ "$media_extension" =~ ^[A-Za-z0-9]{1,8}$ ]] || media_extension="bin"
    remote_media="$remote_root/stress-media.$media_extension"
    "${adb_command[@]}" push "$SOAK_MEDIA" "$remote_media"
    test_method="baseline_media_survives_core_playback_lifecycle"
    instrument_arguments+=(
      -e ycoreSmokeMedia "$remote_media"
      -e ycoreSeekIterations 1000
      -e ycoreSurfaceIterations 1000
    )
    ;;
  continuous_soak)
    media_extension="${SOAK_MEDIA##*.}"
    [[ "$media_extension" =~ ^[A-Za-z0-9]{1,8}$ ]] || media_extension="bin"
    remote_media="$remote_root/continuous-media.$media_extension"
    "${adb_command[@]}" push "$SOAK_MEDIA" "$remote_media"
    test_method="configured_long_running_soak_keeps_output_and_health_stable"
    instrument_arguments+=(
      -e ycoreSoakMedia "$remote_media"
      -e ycoreSoakDurationMinutes 480
      -e ycoreSoakQueue false
    )
    ;;
  queue_soak)
    media_extension="${SOAK_MEDIA##*.}"
    [[ "$media_extension" =~ ^[A-Za-z0-9]{1,8}$ ]] || media_extension="bin"
    remote_media="$remote_root/queue-media.$media_extension"
    "${adb_command[@]}" push "$SOAK_MEDIA" "$remote_media"
    test_method="configured_long_running_soak_keeps_output_and_health_stable"
    instrument_arguments+=(
      -e ycoreSoakMedia "$remote_media"
      -e ycoreSoakDurationMinutes 1440
      -e ycoreSoakQueue true
    )
    ;;
esac

manufacturer="$(get_property ro.product.manufacturer)"
model="$(get_property ro.product.model)"
sdk="$(get_property ro.build.version.sdk)"
chipset="$(get_property ro.soc.model)"
[[ -n "$chipset" ]] || chipset="$(get_property ro.board.platform)"
abi="$(get_property ro.product.cpu.abi)"
[[ "$sdk" =~ ^[0-9]+$ ]] || { echo "error: device SDK is unavailable" >&2; exit 1; }
[[ -n "$chipset" ]] || chipset="unknown"

instrumentation_log="$(mktemp)"
trap 'rm -f "$instrumentation_log"' EXIT
set +e
"${adb_command[@]}" shell am instrument -r -w \
  -e class "com.yfuse.core2.android.YCoreMediaSuiteInstrumentedTest#$test_method" \
  "${instrument_arguments[@]}" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$instrumentation_log"
instrumentation_exit="${PIPESTATUS[0]}"
set -e

mkdir -p "$(dirname "$OUTPUT")"
set +e
python3 "$ROOT_DIR/scripts/ycore-release-evidence.py" collect \
  --instrumentation-log "$instrumentation_log" \
  --output "$OUTPUT" \
  --commit-sha "${COMMIT_SHA,,}" \
  --artifact-sha256 "${ARTIFACT_SHA256,,}" \
  --apk-sha256 "$APK_SHA256" \
  --test-apk-sha256 "$TEST_APK_SHA256" \
  --device-serial "$DEVICE_SERIAL" \
  --manufacturer "$manufacturer" \
  --model "$model" \
  --sdk "$sdk" \
  --chipset "$chipset" \
  --abi "$abi" \
  --profile "$PROFILE" \
  --apk-purity-verified \
  --physical-device-verified
collector_exit="$?"
set -e

if (( instrumentation_exit != 0 )); then
  echo "error: adb instrumentation exited with $instrumentation_exit; evidence was retained at $OUTPUT" >&2
  exit "$instrumentation_exit"
fi
if (( collector_exit != 0 )); then
  echo "error: instrumentation did not satisfy the evidence collector; report retained at $OUTPUT" >&2
  exit "$collector_exit"
fi
echo "YCore $PROFILE evidence: $OUTPUT"
