# Yfuse HarmonyOS (Cangjie)

This is the native HarmonyOS Cangjie port under development. UI, application state, networking and system
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
python3 scripts/verify-harmony-port.py --report build/validation/harmony-source.json
python3 scripts/harmony-release-gate.py
```

The first command checks source contracts and optional host coverage; missing host tooling
is SKIPPED and missing stdx coverage is PARTIAL. It does not build a HAP. Use
`--require-host` to reject incomplete host results, and see
[the validation environment guide](../docs/VALIDATION_ENVIRONMENT.md). The second command
intentionally fails until the Cangjie SDK, production signing and every runtime evidence
gate are present. See `RELEASE_CHECKLIST.md`.

## Capability gate

The current public Cangjie ArkUI wrapper provides `Video`, but still documents `XComponent` and
custom render nodes as unsupported. Therefore:

- system playback is wired through the Cangjie `Video`/AVPlayer surface in source, but needs
  matching SDK/HAP compilation and device evidence before it can ship;
- the native coordinator and C ABI are implemented and host-tested;
- AVCodec/NativeWindow custom rendering must remain disabled until the installed Cangjie SDK exposes
  a supported surface host, or a verified C++ ArkUI native-node bridge is available;
- no release may claim NativeEnhanced, Dolby Vision composition or optical-disc rendering merely
  because FFmpeg/libbluray source is bundled.

This is a release gate, not a request to substitute ArkTS.
