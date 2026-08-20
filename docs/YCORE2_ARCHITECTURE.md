# YCore 2.0 Architecture and Migration Plan

## Decision

YCore 2.0 follows the parallel-migration model:

- same repository and same product;
- current ExoPlayer/libmpv/MDK stack remains **Legacy** and stays production-safe;
- App-facing playback code moves to one stable `YPlayer` API;
- YCore 2.0 is developed beside Legacy and takes formats/routes over incrementally;
- Legacy is removed only after Core2 passes the same media/device regression gates.

The migration is intentionally not a large in-place rewrite of `PlayerRoot`, `ExoVideoEngine`,
`MpvVideoEngine`, or `MdkVideoEngine`.

## Non-negotiable media principle

The preferred video path is always:

```text
compressed bitstream
        ↓
platform hardware decoder
        ↓
Surface / tunneled presentation
        ↓
Android/OEM HDR display pipeline
```

Decoded video must not pass through CPU, `Bitmap`, Compose, or a GPU texture merely to make the
architecture uniform. GPU rendering is a capability fallback for tone mapping/effects, not the
normal playback path.

## Source layout during migration

The first foundation stays inside the existing KMP application module so the new contracts compile
against the same CI immediately and no build-system migration can break production playback.
Package boundaries are deliberately module-shaped and can be extracted to Gradle modules after the
contracts stabilize.

```text
com.yfuse.core2
├── api
│   └── YPlayer / state / tracks / media request
├── capability
│   └── device decoder + display capability model
├── strategy
│   └── deterministic playback route planner
├── graph
│   └── demux / decode / render node composition
├── android
│   ├── Android capability provider
│   └── MediaCodec → Surface video node
└── legacy
    └── LegacyYPlayerAdapter
```

Legacy remains in the existing `com.yfuse.feature.player` and `com.yfuse.core.playback` packages.
Only the compatibility adapter may point from Core2 migration code back to Legacy. Core2 strategy,
capability, graph, bitstream and codec code must not depend on ExoPlayer, mpv, MDK, Emby, Jellyfin,
or Compose types.

## Unified product API

All future playback UI/system integrations should depend on `YPlayer`, not directly on
`VideoEngine`.

During migration:

```text
                 YPlayer
                   │
        ┌──────────┴──────────┐
        │                     │
LegacyYPlayerAdapter       Core2Player
        │                     │
   VideoEngine            PlaybackGraph
  Exo/mpv/MDK          demux + codec + render
```

`YPlayer` intentionally exposes playback intent separately from actual `playing` state so buffering,
watch-together, MediaSession and foreground service behavior do not regress during handovers.

## Route levels

Core2 uses these route tiers:

1. `NativeTunnel` — platform demux + hardware decoder + tunneled/sideband presentation.
2. `NativeDirect` — platform demux + hardware decoder + direct Surface presentation.
3. `NativeEnhanced` — enhanced/custom demux + normalized compressed bitstream + hardware decoder.
4. `GpuEnhanced` — hardware decode plus GPU work only when native output cannot satisfy the target.
5. `SoftwareFallback` — software decode + GPU rendering as the terminal compatibility path.

The route is selected from media requirements plus runtime capabilities, never from a handset model
allowlist. Device quirks are a later corrective layer, not the primary capability source.

## Dolby Vision direction

Core2 must treat Dolby Vision as a bitstream/output route, not a UI badge.

Planned handling:

- MP4 Profile 5/8/10: platform extractor when it preserves required metadata;
- MKV/TS/M2TS: enhanced demux and bitstream normalization, still targeting the Android
  `video/dolby-vision` hardware path where supported;
- Profile 8.1: native DV first, HDR10 compatible-base fallback second;
- Profile 8.4: native DV first, HLG compatible-base fallback second;
- Profile 7: explicit BL/EL/RPU evidence; no FEL claim without verified enhancement-layer
  composition;
- unsupported native HDR output: hardware decode plus GPU tone mapping only where that representation
  is technically valid.

## Migration phases

### Phase 0 — Foundation (this branch)

- [x] introduce stable `YPlayer` API;
- [x] add Legacy adapter without altering production playback;
- [x] add capability model;
- [x] add deterministic route strategy;
- [x] add composable PlaybackGraph node model;
- [x] add Android decoder/display capability probe;
- [x] add first MediaCodec → Surface primitive;
- [x] add route and compatibility tests;
- [x] bind Activity, MediaSession, audio-focus and watch-together controls to `YPlayer`;
- [x] bind PlayerRoot presentation and live queue-extension ownership to `YPlayer`;
- [x] isolate remaining backend-specific quality, subtitle, disc and output extensions behind one
  session capability facade.

### Phase 1 — Native Direct baseline

- Android `MediaExtractor`/Media3 extractor source adapter;
- H.264/H.265 compressed sample pump;
- MediaCodec output directly to `SurfaceView`;
- AAC/AC3 audio baseline;
- prepare/play/pause/seek/flush/recreate-surface lifecycle;
- queue handover and background/foreground stress tests.

### Phase 2 — Capability and Strategy production routing

- exact profile/level/size/rate checks;
- runtime decode probes for questionable vendor claims;
- tunneled playback probe;
- output-route-aware audio capabilities;
- opt-in Core2 route for known-safe SDR/HDR10 files;
- automatic fallback to Legacy on any Core2 startup failure.

### Phase 3 — Enhanced demux and bitstream layer

- FFmpeg/libavformat demux adapter without normal video software decode;
- unified timestamp/sample model;
- Annex-B / length-prefixed conversion;
- AVC/HEVC VPS/SPS/PPS/CSD handling;
- MKV/TS/M2TS path into MediaCodec.

### Phase 4 — HDR and Dolby Vision

- HDR10/HLG/HDR10+ metadata path;
- Dolby Vision config/RPU/BL/EL model;
- Profile 5/8 native route;
- MKV Dolby Vision enhanced-demux route;
- Profile 7 MEL/FEL evidence gates;
- native output verification and HDR-compatible fallback.

### Phase 5 — High-end output

- tunneled video + `HW_AV_SYNC` audio;
- refresh-rate matching/frame pacing;
- TrueHD/E-AC3 Atmos/DTS-HD route-aware passthrough;
- HDMI/eARC/USB/Bluetooth route changes without restarting unrelated nodes.

### Phase 6 — Disc media

- ISO/BDMV/MPLS/CLPI/M2TS graph integration;
- title/chapter/angle/seamless-branching support;
- reuse existing libbluray work behind Core2 demux/navigation contracts;
- keep BD-J and licensed/protected-disc runtime as explicit capability boundaries.

Current compatibility milestone: direct disc items now carry a backend-neutral descriptor through
`YMediaItem` and enter the pinned libmpv/libbluray executor from the adaptive Core2 router. The
temporary executor preserves the original prepared `yfusebd://` or `yfusebdmv://` source, forwards
title/chapter/menu commands through `YPlayer`, and shares the existing HDMV input/overlay plane with
Legacy. Server-resolved disc streams deliberately remain ordinary media streams and do not expose
native navigation. Native Core2 MPLS/CLPI graph nodes, BD-J, protected-disc support, and physical-disc
release evidence remain explicit future gates.

### Phase 7 — GPU and universal fallback

- AHardwareBuffer/Vulkan path;
- PQ/HLG, BT.2020/P3, FP16 processing;
- tone mapping, gamut mapping, dithering/debanding;
- FFmpeg software decode fallback only after native routes are exhausted.

Current compatibility milestone: the adaptive Core2 router can execute `GpuEnhanced` and
`SoftwareFallback` through the repository's pinned libmpv build. The former requests hardware
decode plus libmpv GPU tone mapping; the latter disables hardware decode and uses FFmpeg plus the GPU
renderer. Active `hwdec-current` evidence corrects the reported route if hardware decode falls back
internally. This executor lives under `core2.legacy` and is an explicit bridge, not a claim that the
planned AHardwareBuffer/Vulkan and standalone avcodec graph nodes are complete.

### Phase 8 — Device intelligence and retirement of Legacy

- device/SoC/codec quirk database;
- runtime success/failure memory;
- power/thermal/drop-frame telemetry for route ranking;
- media corpus and physical-device release gates;
- progressively move safe cohorts from Legacy to Core2;
- remove Legacy only when fallback telemetry no longer shows meaningful coverage gaps.

## Merge gates for every Core2 increment

A Core2 change may merge only when:

- Legacy default playback behavior is unchanged unless the PR explicitly migrates a route;
- common and Android Kotlin compile;
- unit tests for policy/state/lifecycle pass;
- lint/ktlint/R8/package gates pass when runners are available;
- no media URL, auth header, DRM key, token, or provider identity is added to diagnostics;
- video output remains direct-to-Surface on native routes;
- a new route has an explicit fallback path and does not poison long-lived device failure memory for
  transient network/auth errors.

## Current production switch

**Core2 is not the default engine.** Legacy remains authoritative, while users can explicitly enable
`YCore 2.0 试用` in advanced playback settings. The switch is persisted separately from the selected
Legacy engine so disabling or failing the trial never changes the user's Exo/mpv/MDK preference.

As of 2026-08-19, the opt-in path has these production boundaries:

- `PlayerRoot` can construct Core2 through the temporary `YPlayerVideoEngineAdapter` and renders its
  direct Android `Surface` through `Core2Surface`;
- each queue item is probed independently and may select `NativeTunnel`, `NativeDirect`, or
  `NativeEnhanced`;
- changing speed or tracks exits `NativeTunnel` at the current position, while a Tunnel runtime
  failure retries the same item on a non-Tunnel Core2 route;
- an exhausted Core2 failure rebuilds the user's selected Legacy engine with the current queue item,
  position, playback intent, and requested speed;
- DRM, unsupported external-subtitle formats/schemes, unsupported media schemes, and otherwise
  ineligible queues bypass the trial and continue directly on Legacy; supported SRT, WebVTT,
  ASS/SSA sidecars stay in Core2; direct ISO/BDMV items may enter the capability-gated compatibility
  executor, while server-resolved disc streams stay on the ordinary media route;
- queue auto-next is owned by the adaptive Core2 layer, with every new item receiving a fresh route
  evaluation;
- eligible episodes discovered during a live series refresh append through `YPlayer` without
  restarting Core2; unsupported additions deliberately trigger the existing Legacy rebuild path.

Activity lifecycle, MediaSession/notification actions, audio focus, runtime monitoring, and
watch-together controls now bind directly to `YPlayer`. A native Core2 player is unwrapped instead
of being routed back through the Legacy adapter. PlayerRoot handovers, network recovery, cast
handoffs, primary track restoration, and ordinary playback controls also call `YPlayer` directly.
PlayerRoot now reads presentation through the active `YPlayer` binding: Legacy preserves its full
state while native Core2 is translated at that single compatibility boundary. Live queue extension
is also owned by the binding, with Legacy engines used only as its fallback implementation.
Backend-specific quality/transcode/output tuning, secondary subtitles, and disc navigation are now
isolated behind `PlayerBackendExtensions`; unsupported Core2 operations continue to return an
explicit unsupported result and preserve the existing rebuild/fallback policy. This completes the
Phase 0 product-control migration without claiming those optional capabilities in the stable
`YPlayer` API. Disc navigation is now part of the stable API and the direct-disc route is executable
through the verified libmpv/libbluray compatibility layer; this is not a claim that native Core2 disc
graph nodes or physical-disc release validation are complete. `GpuEnhanced` and `SoftwareFallback`
are likewise executable through the verified libmpv compatibility executor, including
native-route-to-software runtime fallback for container/decoder/renderer/audio-sink failures.
Network, authorization and DRM failures skip that retry. The native routes render embedded text,
PGS and VobSub plus bounded UTF-8/UTF-16 SRT, WebVTT and ASS/SSA sidecars on an independent overlay;
subtitle selection and delay do not feed decoded video through Compose. HTTP(S) NativeDirect input
uses validated byte ranges and a bounded LRU block cache, while enhanced FFmpeg input applies bounded
reconnects and exposes authorization/network/container failures without persisting URLs or headers.
Deterministic local failures are stored as device-local capability facts and skip only the exact
route after three observations within the seven-day window. Native Vulkan and standalone avcodec
nodes remain Phase 7 replacement work, rather than being misrepresented as already implemented.
Physical-device startup, seek, surface recreation, HDR, audio-route, and background/foreground
regression gates must pass before any eligible cohort can default to Core2.

### Native Direct device evidence (2026-08-20)

The release-signed debug lane passed on a Samsung SM-N960U using the native H.264
`MediaExtractor -> OMX.qcom.video.decoder.avc -> Surface` route:

- 100 deterministic seek/flush/new-frame cycles, split into ten restart-safe segments;
- ten next/previous queue round trips;
- eight pause/detach/recreate/reattach cycles alternating landscape and portrait Surface sizes;
- no player failure, MediaCodec exception, or missing first-frame verification in the completed
  runs.

This run exposed and fixed two lifecycle defects: a late post-seek first frame could be dropped all
the way to EOS, and asynchronously persisted runtime-render evidence could race the next process.
First-frame recovery now renders immediately before normal drop policy resumes, an empty seek can
perform a bounded full decoder reset, and runtime evidence is committed before process handover.
The baseline asset has no audio, so this evidence does not cover A/V drift, passthrough, Dolby
Vision, lock-screen/PiP Activity transitions, or the private licensed media matrix.
