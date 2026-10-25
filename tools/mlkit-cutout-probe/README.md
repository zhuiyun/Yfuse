# Isolated real ML Kit cutout probe

This standalone Java/Android project tests Google's actual Subject Segmentation API on the two
public original posters reported by the user. It does not change Yfuse's production dependencies,
provider selection, version or signed release. Its package is `com.yfuse.cutoutprobe`, version
`0.0.1 (1)`, and is an independently debug-signed diagnostic tool with no player or MDK dependency.

Build with the repository's pinned Gradle and JDK 17:

```sh
python3 tools/mlkit-cutout-probe/fetch_inputs.py
./gradlew -p tools/mlkit-cutout-probe :app:assembleDebug --no-daemon
```

Images are verified against the previously retrieved originals' SHA-256; no source image bytes
are committed. The app shows Google's model-download state, requests the optional module once,
polls for up to ten minutes and then processes each whole original with a maximum long edge of
1024 pixels. Confidence masks and same-coordinate PNG foregrounds are saved with input/output
hashes, dimensions and timing. Model/download/API failures never produce replacement masks.
Raw masks are saved before validation, with non-finite/out-of-range counts and min/max. Only
finite floating-point excursions within 0.001 of [0, 1] may be saturated for PNG/gate use;
both raw and saturated Float32 files and SHA-256 are preserved. Larger excursions fail the
case. Both posters are attempted independently; one failure never hides the other's result.
The model is supplied by Google Play services; the app contains no bundled Google model.

For disposable Google Play services emulators, run `python3 tools/mlkit-cutout-probe/run_device.py`.
If Google reports module-download `INTERNAL_ERROR (8)`, the runner preserves the entire first
attempt and permits one retry on the same emulator after 30 seconds. Real inference failures
are not automatically retried. The real reports of every attempt are retained separately.
The GitHub probe workflow retains JSON, native Float32 masks, PNGs, logs and the exact diagnostic
APK. These are public-poster tests without account login or private media-server configuration.
No private credential, live update or backend deployment is used. If the module cannot download,
that is an environment failure, not proof of segmentation quality. App memory snapshots exclude
Google Play services' separate process; emulator timings are not physical phone performance.

On a physical phone, open the diagnostic app, tap **开始验证两张海报**, and share its report ZIP
after the run. The tool does not install or replace Yfuse. A Google Play services status other
than zero stops processing and reports the compatibility failure.

Official API and optional-module guidance:
- https://developers.google.com/ml-kit/vision/subject-segmentation/android
- https://developers.google.com/android/guides/module-install-apis

Real outputs must still be passed through the current Yfuse crop/quality gate and visually
inspected before recommending a production switch. Successful API calls alone are insufficient.
