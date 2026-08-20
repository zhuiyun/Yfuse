# YCore 22-stage verification

Verified against the 22-stage Android media-platform roadmap on 2026-08-20.

| # | Stage | Runtime evidence | Behavioral evidence |
|---:|---|---|---|
| 1 | Frozen core architecture | `core2/api/YPlayer.kt`, `core2/graph/YPlaybackGraph.kt`, `core2/demux/YDemuxer.kt` keep source, demux, decode, render and clock behind stable interfaces. | `YPlaybackGraphTest`, `YPlayerQueueTest` |
| 2 | Native Direct baseline | `AndroidNativeDirectYPlayer`, `AndroidMediaExtractorDemuxNode`, `AndroidMediaCodecVideoNode` and direct `Surface` output implement prepare/play/pause/seek/flush/recreate/queue transitions without bitmap or YUV readback. | `AndroidMediaCodecVideoNodePolicyTest`, baseline device lane in `YCoreMediaSuiteInstrumentedTest` |
| 3 | Capability engine | `AndroidYCapabilityProvider` reads codec profiles/levels, size/rate, HDR display/audio/tunnel/secure/adaptive/low-latency facts. `AndroidRuntimeCapabilityProbe` and `AndroidRuntimeCapabilityRegistry` distinguish advertised, configured, rendered and rejected evidence. | `YCapabilityTest`, `AndroidRuntimeCapabilityRegistryTest`, runtime probe instrumentation |
| 4 | Strategy engine L0-L4 | `DefaultYPlaybackStrategy` and `AndroidAdaptiveCore2YPlayer` select Native Tunnel, Native Direct, Native Enhanced, GPU Enhanced or Software Fallback per item and output route. | `YPlaybackStrategyTest`, `YTunnelPolicyTest`, `AndroidCore2FallbackPolicyTest` |
| 5 | FFmpeg demux | `AndroidFfmpegDemuxer`/native `ycore_demux_jni.cpp` expose compressed samples, timestamps, codec private data, discontinuity, seek and track selection while keeping video decode in MediaCodec. | `AndroidFfmpegDemuxerMappingTest` and enhanced-session tests |
| 6 | Bitstream normalization | `YBitstream.kt`, `YCodecConfiguration.kt` normalize Annex-B/length prefix, parameter sets and AVC/HEVC/AV1 codec configuration. | `YBitstreamTest`, `YCodecConfigurationTest` |
| 7 | Dolby Vision engine | `YDolbyVisionConfig`, `YDolbyVisionBitstream` and `YDolbyVisionRouter` parse profiles 5/7/8/10, codec family, BL/EL/RPU compatibility and fallback HDR. | Dolby Vision config/encoding/bitstream/router tests |
| 8 | P7/FEL policy | P7 MEL/FEL is identified separately; the router only claims OEM full P7 when proven, otherwise chooses BL+RPU or HDR10 base. Full FEL reconstruction is intentionally not claimed without a licensed implementation. | P7 MEL/FEL truth cases in `YDolbyVisionRouterTest` and product playback-truth tests |
| 9 | HDR engine | `YHdrMetadata` and `YHdrRouter` model SDR, HDR10, HDR10+, HLG and DV with mastering display, MaxCLL/MaxFALL and dynamic metadata routing. | `YHdrMetadataTest`, `YHdrRouterTest` |
| 10 | Tunnel playback | `AndroidNativeTunnelYPlayer`, `AndroidNativeTunnelSession`, tunnel video codec mode and `AndroidTunnelAudioTrackRenderNode` use the shared audio session/HW AV sync and handle seek/reset/surface/audio-route handover. | tunnel strategy/session policy tests and device lane |
| 11 | Frame pacing | `AndroidFrameRateManager` preserves fractional cadence and uses `Surface.setFrameRate` with disabled/seamless/always policy; teardown clears stale hints. | `YFrameRatePolicyTest` covers fractional and valid cadence facts |
| 12 | Audio engine | Native PCM decode and encoded `AudioTrack` passthrough routes cover AAC/FLAC/ALAC/MP3/Opus, AC3/EAC3/JOC, TrueHD/Atmos and DTS/DTS-HD/DTS:X capability families, with live device-route reevaluation. | `AndroidEncodedAudioTrackRenderNodeTest`, strategy audio tests |
| 13 | A/V sync | `YMediaClock`, `YAvSync`, native direct/enhanced sessions and tunnel HW sync use audio-master scheduling, discontinuity reset and bounded correction. | `YMediaClockTest`, `YAvSyncTest` |
| 14 | GPU high-quality fallback | `YGpuPipelinePlanner` explicitly gates Vulkan/mpv GPU processing to tone-map, gamut-map, scale or dither only when direct presentation is insufficient. The production executor uses bundled libmpv GPU with BT.2390 and hardware decode when verified. | `YGpuPipelineTest`, `AndroidMpvCore2FallbackFactoryTest`, HDR policy tests |
| 15 | Universal software fallback | `AndroidAdaptiveCore2YPlayer` moves to software only after unsupported/repeated deterministic local failure; `AndroidMpvCore2FallbackFactory` verifies the actual decoder and reports the honest route. | `AndroidCore2FallbackPolicyTest`, fallback-factory tests |
| 16 | ISO/BDMV | Disc descriptors, native remote ISO Range source, libudfread/libbluray streams, title/chapter selection and main-feature routing feed the same downstream player interfaces. | optical playback, disc inspector, remote preflight and disc fallback tests |
| 17 | Subtitles | `YSubtitle`, parsers and `AndroidExternalSubtitleLoader` keep text/bitmap subtitle selection independent from the direct video Surface. | `YTextSubtitleParserTest` and external subtitle route tests |
| 18 | Network/cache | `YMediaTransport` defines Local/HTTP(S)/WebDAV/SMB/NFS, Range and HTTP/2/3 capabilities; `AndroidHttpRangeMediaDataSource`, remote-disc Range source, `YBufferController` and `YCachePlanner` provide strict range validation and bitrate/memory-aware buffering with credential-free cache identities. | `YMediaTransportTest`, `YBufferControllerTest`, `AndroidHttpRangeMediaDataSourceTest`, remote Range tests |
| 19 | Device quirk database | `YDeviceQuirkDatabase` matches device, SoC, API, decoder, container, codec, HDR/profile and resolution to declarative fixes. Android route evaluation applies shipped rules before strategy selection; no manufacturer `if` chain is used. | `YDeviceQuirkDatabaseTest` |
| 20 | Runtime self-learning | `YPlaybackLearningEngine` persists privacy-safe route metrics for success, dropped frames, codec resets, audio underruns, A/V drift, battery and thermal state. Three failures avoid the exact route; poor tunnel quality falls back to direct. System-image-scoped Android persistence prevents stale cross-update learning. | `YPlaybackLearningTest`, `YCore2FailureLedgerTest`, `AndroidRuntimeCapabilityRegistryTest` |
| 21 | Diagnostics | `YPlayerDiagnostics` exposes container/codec/decoder/demux/render/audio/output truth, drops and A/V drift. Player settings renders it; `DiagnosticLogStore` creates a bounded, redacted `diagnostics.zip` through the one-click export tool. | diagnostics/export and playback-truth tests |
| 22 | Stress/compatibility suite | `YMediaTestSuite` enforces the full codec/DV/HDR/FPS/container/audio/subtitle/resolution/bitrate matrix. `YCoreMediaSuiteInstrumentedTest` executes open/play/100 seeks/pause/resume/track/subtitle/surface/background/foreground/next/finish and emits per-case drop/decoder/memory/thermal/battery/A/V-sync observations. | `YMediaTestSuiteTest`; instrumentation APK compilation; `media-tests/ycore-suite.example.json` |

## Automated gates

- `:composeApp:compileKotlinMetadata :composeApp:compileDebugKotlinAndroid` — passed.
- `:composeApp:testDebugUnitTest` — passed, including all common Core2 tests.
- `:composeApp:ktlintCheck` — passed.
- `:composeApp:compileDebugAndroidTestKotlinAndroid` — passed.
- `:composeApp:assembleDebug :composeApp:assembleDebugAndroidTest` — passed.
- `:composeApp:check` — passed (Debug/Benchmark/Release unit tests, Android lint, ktlint,
  behavioral-test boundaries, design-system and release-signing gates).

## Physical-device gate

A Samsung SM-G973U was detected and a baseline media sample was prepared. The installed
`com.yfuse` uses a different signing certificate from the local Debug APK, so the test target could
not be replaced without uninstalling the user's existing app and its data. No destructive uninstall
was performed. The temporary test APK and media sample were removed. The full licensed matrix must
be run with a matching-signed build and the corpus described by `media-tests/README.md`.
