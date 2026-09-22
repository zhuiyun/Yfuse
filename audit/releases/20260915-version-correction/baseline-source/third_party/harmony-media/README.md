# HarmonyOS native media inputs

Native media dependencies are deliberately not downloaded at configure time. Reproducible, audited
HarmonyOS builds place pinned arm64/x86_64 FFmpeg, libass and libbluray headers/libraries here and
enable the corresponding CMake flags. A missing dependency fails configuration instead of producing
a build that advertises an unavailable capability.

Expected layout:

```
include/libavformat/avformat.h
include/ass/ass.h
include/libbluray/bluray.h
lib/arm64-v8a/libavformat.so
lib/arm64-v8a/libavcodec.so
lib/arm64-v8a/libavutil.so
lib/arm64-v8a/libswresample.so
lib/arm64-v8a/libswscale.so
lib/arm64-v8a/libass.so
lib/arm64-v8a/libbluray.so
```

Never enable `YFUSE_ENABLE_HARMONY_NATIVE_RENDERER` until the Cangjie UI layer can supply a supported
NativeWindow and physical-device decode/render tests pass. Merely bundling a library is not evidence
that HDR, Dolby Vision, encoded audio, menus or optical-disc playback works.
