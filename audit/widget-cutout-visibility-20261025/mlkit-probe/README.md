# Real Google ML Kit Subject Segmentation trial

The user selected on-device Google ML Kit for an actual trial on the two reported posters.
A standalone Java/Android probe was added under `tools/mlkit-cutout-probe`. It has a different
package from Yfuse, no media account access and no production release/deployment side effects.
The official SDK is `play-services-mlkit-subject-segmentation:16.0.0-beta1`.

Local compilation and Android lint passed (zero lint errors, five nonblocking diagnostic-UI
warnings). Both embedded source images match the already-identified public originals' SHA-256.
The diagnostic APK has a debug v2 signature; it is not a production Yfuse package.

## First real cloud run

- Source: `0f67e8ba2cfe2a1f19f9dd4949b859f1f320e7a8`
- Run: https://github.com/zhuiyun/Yfuse/actions/runs/40196336828
- Disposable Android 35 Google Play x86_64 emulator, SwiftShader indirect renderer.
- Google Play services was available (24.23.35 at app check); the optional model downloaded
  successfully after approximately 101 seconds. Native logs loaded `mobile_bg_removal_v8.f16.tflite`.
- Actual processing reached MediaPipe and failed on the first Whalefall image with
  `TensorsToSegmentationCalculator: Tensor should have 2, 3, or 4 dims, received: 0`.
- Native log recorded OpenGL ES 3.0, SwiftShader 4.0.0.1. No foreground mask was produced.
- `first-emulator-report.json` preserves the actual report. This is an API/environment failure;
  it does not establish poor segmentation quality and does not establish physical-device failure.

The official sample has the same failure report in googlesamples/mlkit#915. Its maintainer
points to the emulator OpenGL limitation and issue #783. Android's current emulator guidance
supports SwANGLE; SwiftShader's source documents ANGLE-based GLES 3.1 support.

## Bounded graphics retry

Only the renderer and environment evidence collection were changed: SwANGLE plus Vulkan,
with the same Android 35 system image, exact app Java sources and original images.
- Source: `220b41adc8fef4cb534b59191cdbbcef8d89b113`
- Run: https://github.com/zhuiyun/Yfuse/actions/runs/40197004166
- GLES 3.1 was verified in SurfaceFlinger, but Google module download failed with INTERNAL_ERROR (8). No inference or mask was produced.

## Download recovery

Source `c9662f9987f7fe32c3a62680778fc124a1afca03` retains failed-download evidence and permits exactly one same-device retry after 30 seconds. Actual inference errors are not retried, and real reports remain separate. The Java app and source images were unchanged in this download recovery run. The model became ready after 147 seconds and the SDK returned a mask. The probe then rejected a confidence outside [0, 1] before preserving the raw values. Whalefall had no retained foreground, and Heart of the Beast was not attempted. This is insufficient evidence for a quality conclusion.

## Raw-output capture

Source `2716c0e3e7b9c8f79ecfaa95d932d98acf855ac0` preserves the complete raw mask before validation, records finite/min/max/range statistics, permits saturation only for finite probability roundoff within 0.001, and attempts both posters independently. Raw and saturated bytes have separate hashes. Larger excursions or non-finite values remain failures. Compilation and Android lint passed again.

## Final cloud result and diagnostic handoff

Run https://github.com/zhuiyun/Yfuse/actions/runs/40198448445 used the raw-output capture revision. The Google module became ready after about 61 seconds. Both Whalefall and Heart of the Beast were actually attempted, each with a fresh segmenter after an error. Both failed inside MediaPipe with the same tensor-dimensions-zero error. No raw mask, valid foreground, quality-gate result or meaningful inference-performance conclusion was produced. The numeric-roundoff path therefore remains unexercised on a real mask. Four cloud runs did not establish a segmentation-quality advantage; the GLES 3.1 renderer change did not establish an emulator solution.

The exact final-run debug APK was retrieved from the hash-verified CI archive, its package/version and v2 debug certificate verified, and both embedded original-image hashes checked. It was delivered as `Yfuse-MLKit-probe-0.0.1.apk` (4,149,584 bytes), SHA-256 `ba694f8a209e4467a7774ea04539269c7df2f3a2ae14a5b0c5d8fbe5a573bba0`. It is an independent diagnostic application, not a Yfuse player release, and cannot replace `com.yfuse`.

Next validation requires a physical Android phone with Google Play services. Tap **开始验证两张海报**, then inspect the original/gray-background foregrounds and use **分享验证报告和结果** to return the complete report ZIP. Model/API failures are exported too. No production ML Kit switch, signed Yfuse release or server deployment occurred.


Sources:
- https://developers.google.com/ml-kit/vision/subject-segmentation/android
- https://github.com/googlesamples/mlkit/issues/915
- https://github.com/googlesamples/mlkit/issues/783
- https://developer.android.com/studio/run/emulator-acceleration
- https://android.googlesource.com/platform/external/swiftshader/
