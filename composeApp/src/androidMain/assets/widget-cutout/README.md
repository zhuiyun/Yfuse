# Widget foreground extraction

`u2netp.onnx` is the small U²-Net foreground segmentation model from the U-2-Net project by
Xuebin Qin and collaborators, distributed as an ONNX export by rembg.

- Original project: https://github.com/xuebinqin/U-2-Net
- Paper: https://arxiv.org/abs/2005.09007
- Downloaded model: https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx
- Model size: 4,574,861 bytes
- SHA-256: `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`
- Original project license: Apache License 2.0, reproduced in `U2Net-LICENSE.txt` from
  https://github.com/xuebinqin/U-2-Net/blob/master/LICENSE

The inference dependency is ONNX Runtime Android 1.31.0, copyright Microsoft Corporation,
distributed under the MIT License. `ONNXRuntime-LICENSE.txt` reproduces its release license:
https://github.com/microsoft/onnxruntime/blob/v1.31.0/LICENSE
Its upstream dependency attributions are reproduced in `ONNXRuntime-ThirdPartyNotices.txt` from
https://github.com/microsoft/onnxruntime/blob/v1.31.0/ThirdPartyNotices.txt

The application runs this model locally with ONNX Runtime Android. It sends no artwork to a
segmentation service and requires no Google Play Services. Downloads use the application's
existing Coil image loader. Generated images and rejection records stay in the app's private
cache, with a bounded disk budget and expiry. Inference is serialized, the idle native session
is closed, and leaving the visible route cancels queued work and discards unfinished results.

The quality gate rejects obvious clipping, ambiguous masks, fragments and implausible framing.
It is a heuristic, not a human/limb/title detector: it cannot establish semantic completeness.
Rejected, unsupported, low-memory and failed requests keep the ordinary poster presentation.

The normalized output matte is resized to the bounded source dimensions. The cached original
and alpha foreground share those dimensions, so renderers must transform them together.

The original astronaut preview is separately documented in `PREVIEW-ASTRONAUT.md`, including
its generation prompt and actual-model validation. Its reviewed precomputed foreground is
only selected for that exact bundled asset URL. Arbitrary remote posters always use the runtime
pipeline and quality gate; their results are never replaced with this demo foreground.
