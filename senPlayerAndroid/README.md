# SenPlayer Android build 8 interoperability adapter (experimental)

This is a Yfuse adapter for the user-supplied Android 1.0.0 build 8 binary, not an official SDK.
It is **not runtime-qualified**. A successful source build does not establish that the binary can
play independently of the original app's runtime-license initialization.

## Import and build

The source repository contains the adapter, not the extracted native binaries or original app assets.
The standard build leaves this experimental engine out of the selector. To create a local diagnostic
build with the exact supplied runtime:

```sh
python3 scripts/import-senplayer-runtime.py /path/to/SenPlayer.apk
./gradlew :composeApp:assembleDebug -PyfuseIncludeSenPlayer=true -PyfuseIncludeMdk=true -PyfuseNativeOnlyRuntime=false
```

The importer also accepts the existing decompilation ZIP or its `resources/lib/arm64-v8a` directory.
It validates SHA-256, length and ELF architecture before replacing local files. The Gradle module
verifies the checksums again when explicitly enabled; missing/mismatched binaries fail the build.
No other APK entries, `license.json`, account state or app services are copied.

The settings option is available only in enabled builds, on Android 9+ and a 64-bit ARM process.
Choose `SenPlayer（实验）` in playback mode. Auto selection never chooses this engine.
DRM/special platform output and authenticated NAS transports keep their existing routing rules.
Custom-header sources are rejected by this adapter until header scoping across redirects is tested.

## Implemented source path

- JNI method descriptors and constructor layouts tied to build 8; R8 retention for all native entry points.
- One worker owns open, commands, polling, surface changes and native release.
- Play/pause, seek, speed, queue entry change, end-of-item behavior, retry and track selection.
- Native media-time cache ranges are represented separately from the forward-buffer endpoint.
- Source IDs reject stale snapshots; session teardown clears the native owner before closing it.
- Errors omit native text that may contain authenticated URLs. Native crash attribution names SenPlayer.
- The experimental engine does not advertise unverified passthrough or frame-rate matching.
- Anonymous QoE upload skips this engine until the deployed server supports its enum value.

## Evidence and open blockers

Input APK: `com.wuziqi.SenPlayer`, version 1.0.0/build 8, SHA-256
`45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c`.

`libFFmpegKit.so` is dynamically registered through JNI_OnLoad; this name alone does not identify
the public FFmpegKit SDK. Its bridge uses `com.mplaykit.N` and build-specific array constructors.
The original app also invokes `runtimePermit` after a ProductRules license check through MLibKit.
This adapter does **not** synthesize a permit, modify that check or copy the original app's license.
Whether a usable independent runtime can initialize remains unverified; a refusal is surfaced as an error.

Before promotion: ARM64 device checks must cover JNI registration, initialization/license behavior,
video and audio output, hardware/software decoding, subtitles, seek/range reporting, background/surface
recreation, repeated open/fail/retry, and release. Check final APK native dependencies, including which
`libc++_shared.so` the existing multi-engine packaging selects. Different C++ runtimes must not be assumed
interchangeable. No ARM64 runtime pass or signed install package has been produced for this adapter.
