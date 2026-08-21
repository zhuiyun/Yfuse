# YCore 22-stage verification

Verified against the 22-stage Android media-platform roadmap on 2026-08-20, with a merged-head
physical-device regression rerun on 2026-08-21.

| # | Stage | Runtime evidence | Behavioral evidence |
|---:|---|---|---|
| 1 | Frozen core architecture | `core2/api/YPlayer.kt`, `core2/graph/YPlaybackGraph.kt`, `core2/demux/YDemuxer.kt` keep source, demux, decode, render and clock behind stable interfaces. | `YPlaybackGraphTest`, `YPlayerQueueTest` |
| 2 | Native Direct baseline | `AndroidNativeDirectYPlayer`, `AndroidMediaExtractorDemuxNode`, `AndroidMediaCodecVideoNode` and direct `Surface` output implement prepare/play/pause/seek/flush/recreate/queue transitions without bitmap or YUV readback. | `AndroidMediaCodecVideoNodePolicyTest`, baseline device lane in `YCoreMediaSuiteInstrumentedTest` |
| 3 | Capability engine | `AndroidYCapabilityProvider` reads codec profiles/levels, size/rate, HDR display/audio/tunnel/secure/adaptive/low-latency facts. `AndroidCodecSampleProbe` queues real compressed samples from the current item and records `Rendered` only after a MediaCodec Surface callback; enhanced-only formats retain the bounded configure/start probe until their normal playback produces the same evidence. | `YCapabilityTest`, `AndroidRuntimeCapabilityRegistryTest`, runtime probe instrumentation |
| 4 | Strategy engine L0-L4 | `DefaultYPlaybackStrategy` and `AndroidAdaptiveCore2YPlayer` select Native Tunnel, Native Direct, Native Enhanced, GPU Enhanced or Software Fallback per item and output route. | `YPlaybackStrategyTest`, `YTunnelPolicyTest`, `AndroidCore2FallbackPolicyTest` |
| 5 | FFmpeg demux | `AndroidFfmpegDemuxer`/native `ycore_demux_jni.cpp` expose compressed samples, timestamps, codec private data, discontinuity, seek and track selection while keeping video decode in MediaCodec. | `AndroidFfmpegDemuxerMappingTest` and enhanced-session tests |
| 6 | Bitstream normalization | `YBitstream.kt`, `YCodecConfiguration.kt` normalize Annex-B/length prefix, parameter sets and AVC/HEVC/AV1 codec configuration. | `YBitstreamTest`, `YCodecConfigurationTest` |
| 7 | Dolby Vision engine | `YDolbyVisionConfig`, `YDolbyVisionBitstream` and `YDolbyVisionRouter` parse profiles 5/7/8/10, codec family, BL/EL/RPU compatibility and fallback HDR. Production route evaluation now invokes the router and reports the chosen DV path in diagnostics. | Dolby Vision config/encoding/bitstream/router tests |
| 8 | P7/FEL policy | P7 MEL, FEL and unknown enhancement-layer evidence are separate states; the router only claims OEM full P7/FEL when explicit FEL parsing and independent composed-output evidence both exist, otherwise it chooses BL+RPU or HDR10 base. Full FEL reconstruction is intentionally not claimed without a licensed implementation. | P7 MEL/FEL/unknown truth cases in `YDolbyVisionRouterTest` and product playback-truth tests |
| 9 | HDR engine | `YHdrMetadata` and the production strategy's `YHdrRouter` model SDR, HDR10, HDR10+, HLG and DV with mastering display and MaxCLL/MaxFALL. Enhanced HEVC playback extracts registered ITU-T T.35 HDR10+ SEI and applies it to MediaCodec per access unit. | `YHdrMetadataTest`, `YHdrRouterTest`, `YBitstreamTest` |
| 10 | Tunnel playback | `AndroidNativeTunnelYPlayer`, `AndroidNativeTunnelSession`, tunnel video codec mode and `AndroidTunnelAudioTrackRenderNode` use the shared audio session/HW AV sync and handle seek/reset/surface/audio-route handover. | tunnel strategy/session policy tests and device lane |
| 11 | Frame pacing | `AndroidFrameRateManager` preserves fractional cadence, queries real display modes, selects exact/integer-multiple refresh targets, avoids unnecessary black-screen switches and uses `Surface.setFrameRate`; teardown clears stale hints and VFR never claims fixed cadence. | `YFrameRatePolicyTest` covers fractional multiples, current-mode retention and VFR |
| 12 | Audio engine | Native PCM decode and encoded `AudioTrack` passthrough routes cover AAC/FLAC/ALAC/MP3/Opus, AC3/EAC3/JOC, TrueHD/Atmos and DTS/DTS-HD/DTS:X capability families, with live device-route reevaluation. | `AndroidEncodedAudioTrackRenderNodeTest`, strategy audio tests |
| 13 | A/V sync | `YMediaClock`, `YAvSync`, native direct/enhanced sessions and tunnel HW sync use audio-master scheduling, discontinuity reset and bounded correction. | `YMediaClockTest`, `YAvSyncTest` |
| 14 | GPU high-quality fallback | `YGpuPipelinePlanner` explicitly gates Vulkan/mpv GPU processing to tone-map, gamut-map, scale, deband or dither only when direct presentation is insufficient. The production executor uses bundled libmpv/libplacebo GPU with BT.2390, perceptual gamut mapping and hardware decode when verified. A native Vulkan label additionally requires a real Vulkan executor, AHardwareBuffer import, presented output and measured evidence. | `YGpuPipelineTest`, `AndroidMpvCore2FallbackFactoryTest`, HDR policy tests |
| 15 | Universal software fallback | `AndroidAdaptiveCore2YPlayer` moves to software only after unsupported/repeated deterministic local failure; `AndroidMpvCore2FallbackFactory` verifies the actual decoder and reports the honest route. | `AndroidCore2FallbackPolicyTest`, fallback-factory tests |
| 16 | ISO/BDMV | Disc descriptors, native remote ISO Range source, libudfread/libbluray streams, title/chapter selection and main-feature routing feed the same downstream player interfaces. | optical playback, disc inspector, remote preflight and disc fallback tests |
| 17 | Subtitles | `YSubtitle`, ASS/SSA/SRT/WebVTT parsers and `AndroidExternalSubtitleLoader` keep text/bitmap subtitle selection independent from the direct video Surface. ASS named styles and inline bold/italic/underline/font-size/color/alignment/outline/shadow overrides render in the Compose overlay without changing video to TextureView/GPU composition. | `YTextSubtitleParserTest` and external subtitle route tests |
| 18 | Network/cache | `YMediaTransport` has concrete OkHttp HTTP(S)/WebDAV Range, jcifs-ng SMB2/3 random-access and Cronet HTTP/2/HTTP/3 executors. HTTP prefers Cronet and safely falls back to OkHttp; SMB/WebDAV are admitted by the production Core2 source gate. `AndroidTransportMediaDataSource`, `YBufferController` and `YCachePlanner` provide bounded cached random access and bitrate/memory-aware buffering. | `YMediaTransportTest`, `AndroidAdaptiveHttpMediaTransportTest`, `YBufferControllerTest`, remote Range tests |
| 19 | Device quirk database | `YDeviceQuirkDatabase` matches device, SoC, API, decoder, container, codec, HDR/profile and resolution to declarative fixes. Android route evaluation applies shipped rules before strategy selection; no manufacturer `if` chain is used. | `YDeviceQuirkDatabaseTest` |
| 20 | Runtime self-learning | `YPlaybackLearningEngine` persists privacy-safe route metrics for success, dropped frames, codec resets, audio underruns, A/V drift, battery and thermal state. Three failures avoid the exact route; poor tunnel quality falls back to direct. System-image-scoped Android persistence prevents stale cross-update learning. | `YPlaybackLearningTest`, `YCore2FailureLedgerTest`, `AndroidRuntimeCapabilityRegistryTest` |
| 21 | Diagnostics | `YPlayerDiagnostics` exposes container/codec/decoder/demux/render/audio/output truth, drops and A/V drift. Player settings renders it; `DiagnosticLogStore` creates a bounded, redacted `diagnostics.zip` through the one-click export tool. | diagnostics/export and playback-truth tests |
| 22 | Stress/compatibility suite | `YMediaTestSuite` enforces the full codec/DV/HDR/FPS/container/audio/subtitle/resolution/bitrate matrix. `YCoreMediaSuiteInstrumentedTest` verifies selected audio/subtitle state, performs ten matrix seeks, waits for natural `Ended`, and executes surface/background/foreground/next operations while collecting drop/decoder/memory/thermal/battery/A/V-sync observations. | `YMediaTestSuiteTest`; instrumentation APK compilation; `media-tests/ycore-suite.example.json`; Samsung baseline run below |

## Automated gates

- `:composeApp:compileKotlinMetadata :composeApp:compileDebugKotlinAndroid` — passed.
- `:composeApp:testDebugUnitTest` — passed, including all common Core2 tests.
- `:composeApp:ktlintCheck` — passed.
- `:composeApp:compileDebugAndroidTestKotlinAndroid` — passed.
- `:composeApp:assembleDebug :composeApp:assembleDebugAndroidTest` — passed.
- `:composeApp:check` — passed (Debug/Benchmark/Release unit tests, Android lint, ktlint,
  behavioral-test boundaries, design-system and release-signing gates).
- `:composeApp:assembleRelease` — passed after R8, release vital lint, resource optimization and
  signing validation.

## Physical-device gate

To preserve the differently signed installed production app, an isolated temporary application id
was used on a Samsung SM-G973U (Android 10). The baseline instrumented lane passed in 96.422 seconds:

- 100/100 seek verification cycles;
- 8/8 Surface detach/recreate cycles across landscape and portrait sizes;
- pause/resume and background/foreground detach/reattach;
- next/previous episode round trip;
- no timeout and no decoder failure;
- peak PSS 182,141,952 bytes;
- 58 frame drops accumulated across the deliberately adversarial 100-seek run.

The two temporary packages and copied media were removed after the run; the installed `com.yfuse`
application and its data were not changed. The run also caught and fixed Android 10 API verifier,
probe-Surface and duplicate-initialization races before passing. The
full licensed DV/HDR/audio/container matrix remains reproducible through the corpus described by
`media-tests/README.md`; licensed samples are intentionally not committed to the repository.

The merged head was independently rerun with an isolated `com.yfuse.validation` package on a
Samsung SM-N960U (Android 10). A 28.445-second locally recorded H.264 baseline passed in 109.702
seconds:

- 100/100 deterministic seek/reset/new-frame cycles;
- 8/8 Surface detach/recreate cycles;
- background/foreground and next/previous item round trips;
- natural `Ended` verification;
- no timeout or decoder failure;
- peak PSS 173,762,560 bytes and 216 accumulated dropped frames during the adversarial seek run.

This rerun exposed and fixed a short-media race in the test harness: media could naturally reach
`Ended` between iterations, where `seekTo` correctly does not imply autoplay. Each stress iteration
now explicitly calls `play` after seeking. The original installed `com.yfuse` package and data were
not modified. The temporary validation packages and their generated media were removed after the
successful run.

## Truth boundary

- Native Vulkan/AHardwareBuffer presentation is never reported unless a native executor imports,
  presents and measures the output. The shipping high-quality GPU fallback is libmpv/libplacebo.
- Full Dolby Vision P7 FEL reconstruction is never reported without explicit FEL evidence and a
  licensed composed-output implementation. Compatible BL+RPU/HDR10 fallback is labelled as such.
- The repository ships the complete matrix schema, validation and runner, but not third-party
  licensed media. The baseline lane above is physical-device evidence; a full matrix release claim
  additionally requires mounting the licensed corpus described in `media-tests/README.md`.
