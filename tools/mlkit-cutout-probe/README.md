# Isolated real ML Kit cutout probe

This standalone Java/Android project tests Google's actual Subject Segmentation API on the two
public original posters reported by the user. It does not change Yfuse's production dependencies,
provider selection, version or signed release. Its v2 diagnostic package is `com.yfuse.cutoutprobe.v2`, version
`0.0.2 (2)`, and is an independently debug-signed diagnostic tool with no player or MDK dependency.
It can coexist with the old 0.0.1 tool; a fresh CI debug key cannot overwrite that old signature.
This standalone project owns its own root `version.properties` and `release-notes.txt`.

Build with the repository's pinned Gradle and JDK 17:

```sh
python3 tools/mlkit-cutout-probe/fetch_inputs.py
./gradlew -p tools/mlkit-cutout-probe :app:assembleDebug :app:lintDebug --no-daemon
```

Images are verified against the previously retrieved originals' SHA-256; no source image bytes
are committed. The app shows Google's model-download state, requests the optional module once,
polls for up to ten minutes and then processes each whole original with a maximum long edge of
1024 pixels. Confidence masks and same-coordinate PNG foregrounds are saved with input/output
hashes, dimensions and timing. Model/download/API failures never produce replacement masks.
Raw masks are saved before validation, with non-finite/out-of-range counts and min/max.
All finite values may be clipped to [0, 1] for a **diagnostic preview only**; excursions beyond
0.001 remain explicitly marked `out_of_range_beyond_roundoff` and are excluded from the
legacy numerical-valid count. They do not hide either real SDK output or the diagnostic
preview. NaN/infinities still block mask reconstruction, but preserve the raw mask and any
SDK foreground bitmap. No clipping is a confidence/quality pass or a production-provider switch.
Both posters are attempted independently; one failure never hides the other's result.

The same inference enables both `enableForegroundConfidenceMask()` and
`enableForegroundBitmap()`. The report saves the SDK bitmap separately, compares only
same-coordinate alpha (MAE/max error in 0..255, IoU at alpha 128 and disagreement above 8),
and records blank/full SDK masks. These are same-inference diagnostics, not independent
semantic validation. `quality_verdict` always remains `not_evaluated`.

The app shows three columns and supports up to eight user-selected posters via the system
document picker, without storage permissions or automatic picture/report upload. Inputs are
processed sequentially; decode/preview sizes are bounded. Each run replaces the previous
local report, so share any report that must be retained before starting another batch.
Source PNGs are the actual decoded/resized model inputs (encoded-file and PNG hashes differ).
Numerical errors, body/text remnants and non-prominent subjects still need visual inspection.
The model is supplied by Google Play services; the app contains no bundled Google model.

For disposable Google Play services emulators, run `python3 tools/mlkit-cutout-probe/run_device.py`.
If Google reports module-download `INTERNAL_ERROR (8)`, the runner preserves the entire first
attempt and permits one retry on the same emulator after 30 seconds. Real inference failures
are not automatically retried. The real reports of every attempt are retained separately.
On push, CI runs mask regression, assembly, lint and actual APK metadata/v2 signature checks.
The optional emulator is now a manual `run_emulator` choice; four previous emulator inference
failures do not establish physical-device quality. CI retains the exact verified diagnostic APK,
version/hash/signature metadata and lint evidence; emulator runs also retain JSON, Float32, PNGs and logs. These are public-poster tests without account login or private media-server configuration.
No private credential, live update or backend deployment is used. If the module cannot download,
that is an environment failure, not proof of segmentation quality. App memory snapshots exclude
Google Play services' separate process; emulator timings are not physical phone performance.

On a physical phone, open the diagnostic app, tap **开始验证两张海报**, and share its report ZIP
after the run, or tap **选择其他海报验证** for another local batch. The tool does not install or replace Yfuse. A Google Play services status other
than zero stops processing and reports the compatibility failure.

Official API and optional-module guidance:
- https://developers.google.com/ml-kit/vision/subject-segmentation/android
- https://developers.google.com/android/guides/module-install-apis

Real outputs must still be passed through the current Yfuse crop/quality gate and visually
inspected before recommending a production switch. Successful API calls alone are insufficient.

Regression without Android or Google model:

```sh
javac -d /tmp/mlkit-mask-tests app/src/main/java/com/yfuse/cutoutprobe/MaskAnalysis.java tests/MaskAnalysisTest.java
java -cp /tmp/mlkit-mask-tests com.yfuse.cutoutprobe.MaskAnalysisTest
```

The Java runner also accepts real `.f32` paths to check byte-for-byte preservation and
diagnostic recovery from the OPPO report without rerunning inference. No report/device
metadata or user-selected image bytes are committed to this public repository.
