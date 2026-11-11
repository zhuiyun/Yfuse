#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/native-test-build.sh
source "$ROOT/scripts/native-test-build.sh"

ycore_native_test ycore-demux-seek-test "$ROOT/scripts/native/ycore_demux_seek_test.cpp"
# Guard the JNI integration as well as the standalone policy: a failed seek must return
# before restoring discard flags, and the successful seek must flush before restoration.
python3 - "$ROOT/scripts/native/ycore_demux_jni.cpp" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()
seek = source.split("jint native_seek(", 1)[1].split("static const JNINativeMethod", 1)[0]
failure = seek.index("if (error < 0) {\n        return failure_status(error, session->remote_source);\n    }")
flush = seek.index("avformat_flush(session->format);")
restore = seek.index("ycore_demux::restore_selected_streams_after_seek(")
assert failure < flush < restore, "Selected-stream restoration must follow a successful seek and flush"
assert seek.count("restore_selected_streams_after_seek(") == 1
PY
echo "[ycore-demux-seek] tests passed"
