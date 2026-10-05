# YCore native ABI

**Experimental — HarmonyOS port only.** The Android player does not use this module: Android YCore
is the Kotlin `com.yfuse.core2` package with its media libraries in `libycore_demux` and
`libycore_gpu` (built from `scripts/native`). The HarmonyOS port that binds this ABI has not been
built into a HAP or run on a device; see `harmonyApp/PORT_STATUS.md`.

This module is the boundary between the port's managed code and its native media backends. The
header is plain C so HarmonyOS Cangjie FFI and host tests use the same ABI. Struct fields and
parameters are fixed-width integers (the enums only name their values), and the header states the
threading and string-lifetime contract a host must follow.

The coordinator owns backend selection, state preservation and eligible fallback. It intentionally
does not decode media. Harmony backends bind the vtable to AVPlayer or AVCodec/NativeWindow;
enhanced routes may use FFmpeg for demuxing and bitstream normalization.

Authorization and DRM failures never trigger an automatic backend change because another decoder
cannot repair invalid credentials or a missing license. Container, decoder, renderer, audio and
ordinary network failures may hand over while preserving playback intent, position, speed and track
selection.

Host verification (the tests' checks stay active in Release builds):

```sh
cmake -S ycore-native -B build/ycore-native -DCMAKE_BUILD_TYPE=Release
cmake --build build/ycore-native
ctest --test-dir build/ycore-native --output-on-failure
```

`scripts/verify-harmony-port.py` also builds the tests under AddressSanitizer and
UndefinedBehaviorSanitizer when the host compiler has them.
