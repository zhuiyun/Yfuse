# YCore native ABI

This module is the only boundary between managed application code and native media backends. The
header is plain C so Android JNI, HarmonyOS Cangjie FFI and host tests use the same ABI.

The coordinator owns backend selection, state preservation and eligible fallback. It intentionally
does not decode media. Harmony backends bind the vtable to AVPlayer or AVCodec/NativeWindow;
enhanced routes may use FFmpeg for demuxing and bitstream normalization.

Authorization and DRM failures never trigger an automatic backend change because another decoder
cannot repair invalid credentials or a missing license. Container, decoder, renderer, audio and
ordinary network failures may hand over while preserving playback intent, position, speed and track
selection.

Host verification:

```sh
cmake -S ycore-native -B build/ycore-native
cmake --build build/ycore-native
ctest --test-dir build/ycore-native --output-on-failure
```
