# YCore physical-device release gates

This repository contains the instrumented runner and an 18-case manifest contract, but it does not
contain copyrighted or Dolby-licensed media. Build a private corpus from
`ycore-suite.example.json`, preserve the case IDs and metadata, and name the populated manifest
`ycore-suite.json`.

The device runner validates the manifest before playback. For every non-disc case it compares the
declared codec, bit depth, HDR/Dolby profile, frame rate, container, audio/subtitle tracks, height,
and bitrate with facts observed by the bundled FFmpeg demuxer. ISO and BDMV cases carry an explicit
disc descriptor and must complete through YCore's libbluray route; they are never treated as
ordinary files. Playback fails closed on a non-native route, server transcode, decoder failure,
first-frame timeout, stale output after seek, track-switch failure, Surface recreation failure,
queue failure, or an incomplete natural finish.

## Build the isolated native-only test pair

Place the verified dependency-closed AAR at `composeApp/libs/ycore-native.aar`, then build an
isolated application ID so an existing installation is not replaced:

```bash
./gradlew --max-workers=1 \
  :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest \
  -PyfuseNativeOnlyRuntime=true \
  -PyfuseApplicationId=com.yfuse.validation
```

The APK verifier requires `unzip` and `readelf`. The device runner additionally requires `adb`,
Python 3, the application APK, matching androidTest APK, source commit SHA, and SHA-256 of the exact
`ycore-native.aar` used by the build.

## Collect one redacted report per profile

For publishable evidence, download both artifacts from one successful `Build YCore native runtime`
run. This prevents a locally rebuilt APK or a stale androidTest APK from being paired with the
published AAR:

```bash
gh run download "$SOURCE_RUN_ID" --name ycore-native-arm64 --dir build/ycore-source
gh run download "$SOURCE_RUN_ID" --name ycore-device-test-apks --dir build/ycore-device-tests
```

Set the common inputs once. Select a device explicitly when more than one is connected:

```bash
export YCORE_APP_APK="$PWD/build/ycore-device-tests/ycore-native-test-app.apk"
export YCORE_TEST_APK="$PWD/build/ycore-device-tests/ycore-native-test-androidTest.apk"
export YCORE_COMMIT_SHA="source-run-head-sha"
export YCORE_ARTIFACT_SHA256="$(sha256sum build/ycore-source/ycore-native.aar | awk '{print $1}')"
export YCORE_DEVICE_SERIAL="device-serial-from-adb"
```

Run the full licensed matrix on at least four physical devices. Every qualifying device must pass
all 18 cases; the four devices together must span at least three reported SoC families:

```bash
YCORE_PROFILE=matrix \
YCORE_CORPUS_DIR="$PWD/private-ycore-suite" \
YCORE_EVIDENCE_OUTPUT="$PWD/build/ycore-evidence/matrix-device-a.json" \
./scripts/run-ycore-device-gates.sh
```

Use a representative local H.264 file for the operation and soak profiles. The stress profile
performs 1,000 verified seeks and 1,000 verified Surface detach/recreate/new-frame cycles. The
continuous soak is one uninterrupted 8-hour run. The queue soak is one uninterrupted 24-hour run
and forces an output-verified episode transition every 10 minutes, producing at least 100 actual
queue transitions rather than inferring them from elapsed time.

```bash
YCORE_PROFILE=stress YCORE_SOAK_MEDIA="$PWD/baseline.mp4" \
YCORE_EVIDENCE_OUTPUT="$PWD/build/ycore-evidence/stress.json" \
./scripts/run-ycore-device-gates.sh

YCORE_PROFILE=continuous_soak YCORE_SOAK_MEDIA="$PWD/baseline.mp4" \
YCORE_EVIDENCE_OUTPUT="$PWD/build/ycore-evidence/continuous-soak.json" \
./scripts/run-ycore-device-gates.sh

YCORE_PROFILE=queue_soak YCORE_SOAK_MEDIA="$PWD/baseline.mp4" \
YCORE_EVIDENCE_OUTPUT="$PWD/build/ycore-evidence/queue-soak.json" \
./scripts/run-ycore-device-gates.sh
```

Profiles and devices may run in parallel, but do not split either soak: the verifier deliberately
uses the longest single completed observation and never adds shorter runs together. It only counts
Seek, Surface, and queue operations reported after successful output verification.

## Merge and enforce the release decision

Merge only reports from the same commit, YCore AAR, application APK, and androidTest APK. Exact
duplicate runs are deduplicated by a deterministic run ID:

```bash
python3 scripts/ycore-release-evidence.py merge \
  --output build/ycore-evidence/all.json \
  build/ycore-evidence/matrix-*.json \
  build/ycore-evidence/stress.json \
  build/ycore-evidence/continuous-soak.json \
  build/ycore-evidence/queue-soak.json

python3 scripts/ycore-release-evidence.py verify \
  --evidence build/ycore-evidence/all.json \
  --suite media-tests/ycore-suite.example.json \
  --output build/ycore-evidence/release-gates.json
```

`verify` exits nonzero unless every gate is `Pass`. Missing measurements stay `NotMeasured`; a
partial matrix or insufficient count is `Fail`. Reports store only a SHA-256 hash of the adb serial,
not the raw serial. The temporary instrumentation log remains local and is deleted after collection.

For development, the individual instrumentation methods can still be invoked directly with
`ycoreMediaManifest`, `ycoreSmokeMedia`, or `ycoreSoakMedia`. Direct invocations are useful for
diagnosis but do not create release evidence unless their output is passed through the collector.
