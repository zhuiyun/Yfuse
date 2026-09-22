# Yfuse HarmonyOS (Cangjie)

This is the native HarmonyOS product implementation. UI, application state, networking and system
integration are written in Cangjie. Native media libraries are exposed only through the stable C ABI
in `../ycore-native`.

## Required toolchain

- DevEco Studio with the matching Cangjie plugin and HarmonyOS API 20+ SDK
- Cangjie compiler/package manager supplied by that SDK
- HarmonyOS native LLVM/CMake toolchain for `arm64-v8a`
- A local signing profile configured in DevEco Studio

The repository does not commit private signing material or an SDK path. Generate the signing block
locally, then run the module's Release HAP task.

Run the repository checks with:

```bash
python3 scripts/verify-harmony-port.py
python3 scripts/harmony-release-gate.py
```

The first command is SDK-independent. The second intentionally fails until the Cangjie SDK,
production signing and every runtime evidence gate are present. See `RELEASE_CHECKLIST.md`.

## Capability gate

The current public Cangjie ArkUI wrapper provides `Video`, but still documents `XComponent` and
custom render nodes as unsupported. Therefore:

- system playback can ship through the Cangjie `Video`/AVPlayer surface;
- the native coordinator and C ABI are implemented and host-tested;
- AVCodec/NativeWindow custom rendering must remain disabled until the installed Cangjie SDK exposes
  a supported surface host, or a verified C++ ArkUI native-node bridge is available;
- no release may claim NativeEnhanced, Dolby Vision composition or optical-disc rendering merely
  because FFmpeg/libbluray source is bundled.

This is a release gate, not a request to substitute ArkTS.
