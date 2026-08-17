# YCore release validation matrix

YCore's policy and state machines are covered by unit tests. A release claim such as “stable”,
“HDR compatible” or “lower power” additionally requires the physical-device and media-corpus gates
below. CI cannot replace these measurements.

## Device lanes

| Lane | Minimum coverage | Required scenarios |
| --- | --- | --- |
| Android | 8, 10, 12, 14, 15, 16 | foreground, background, lock, PiP, audio focus |
| SoC | Qualcomm, MediaTek, Exynos/Google Tensor | hardware decode, software fallback, thermal pressure |
| Display | SDR 60 Hz, SDR 90/120 Hz, HDR10, HDR10+, HLG, Dolby Vision | refresh matching, tone mapping, brightness |
| Audio | speaker, wired, Bluetooth, HDMI/eARC | PCM, E-AC3, TrueHD/Atmos capability and fallback |
| Network | stable Wi-Fi, metered, loss, latency, handover | downgrade, recovery, no oscillation, reconnect |

## Corpus lanes

- Containers: MP4/MOV, MKV, MPEG-TS, WebM, AVI, FLV, ISO, DVD and BDMV.
- Video: H.264 8/10-bit, HEVC Main/Main10, VP9, AV1, MPEG-2 and VC-1 where available.
- Dynamic range: SDR, HDR10, HDR10+, HLG and every Dolby Vision profile the device advertises.
- Audio: AAC, MP3, FLAC, Opus, AC-3, E-AC3/JOC, DTS variants and TrueHD/Atmos.
- Subtitles: SRT/WebVTT, ASS/SSA effects, PGS, VobSub and DVB; single, dual and offset tracks.
- UHD Blu-ray: local ISO/BDMV and server-resolved M2TS/TS main-feature streams; HDR10/HDR10+/HLG,
  Dolby Vision P7/P8 where legally available, TrueHD/Atmos, DTS-HD and PGS.
- Large media: 4 GiB+ and 100 GiB+ samples must cover random seek, resume and EOF without 32-bit
  offset truncation. Include MOV/ProRes and Blu-ray image/main-feature cases when available.
- Faults: truncated manifests, corrupt timestamps, missing tracks, slow origin, 401/403/404/5xx,
  discontinuities and random seek/track/subtitle operations.

Every sample has a redacted manifest entry containing capability signature, expected route, expected
output and allowed fallback. Media URLs, tokens, account ids and server ids must never be committed.

### UHD Blu-ray route gates

- A valid server-resolved `.m2ts` / `.mts` / `.ts` main feature must remain direct-stream playback;
  the original MediaSource being ISO/BDMV is not by itself a reason to start server ffmpeg.
- A raw remote ISO/BDMV without a resolved linear stream must still use the server main-feature
  fallback rather than being treated as an ordinary HTTP video file.
- PGS may change the local backend to the native subtitle renderer, but must not change a valid
  direct-stream URL into a server transcode.
- TrueHD/Atmos or DTS-HD is reported as passthrough only when the active Android route proves encoded
  output; speaker/Bluetooth fallback to PCM is a passing outcome.
- Dolby Vision P7 FEL is `NotMeasured` unless a physical-device trace proves the enhancement layer is
  being composed. Base-layer playback is not sufficient evidence for an FEL-support claim.
- HDMV interactive menus and BD-J remain unsupported until a backend/runtime exposes verifiable
  navigation; unit tests must not simulate these into a supported state.

## Release gates

| Signal | Gate |
| --- | --- |
| Crash-free playback sessions | at least 99.9% in the staged cohort |
| Automatic recovery success | at least 95% for eligible backend faults |
| A/V sync | absolute error no greater than 80 ms after seek and handover |
| Steady-state dropped frames | below 1% on a device-supported stream |
| Rebuffer ratio | below 1% when measured throughput is at least 1.5x media bitrate |
| Handover | preserves pause, speed, position within 250 ms, audio and subtitle intent |
| Power | no regression above 5% versus the same Media3 hardware path; power mode target is 10% lower |
| Soak | 8-hour queue and 24-hour single-item runs without leak, ANR or thermal runaway |

Startup, rebuffer, dropped-frame, thermal and device-wide power results must include P50/P95 and the
exact device/build. Route-based estimates in diagnostics are labels, not proof of energy savings.

## Evidence evaluation

`evaluatePlaybackReleaseGates(PlaybackReleaseValidationInput)` is the shared evaluator for the numeric
gates above. It reports `Pass`, `Fail` or `NotMeasured` for every gate and calculates nearest-rank
P50/P95/max distributions for startup, automatic recovery time, A/V sync, dropped frames, eligible
rebuffer samples, handover position error, power regression and thermal headroom.

Missing samples are deliberately `NotMeasured`, never a pass. A report can be release-ready only when
all numeric gates have evidence and pass. Device/corpus lane coverage is still checked by the release
workflow: one passing device does not satisfy this matrix. Store only redacted numeric observations;
exact device/build labels belong in the validation artifact, never in anonymous QoE reports. QoE may
carry only the protocol's fixed SoC-vendor enum so codec regressions can be grouped without a model id.

The repository unit tests validate evaluator math and boundary behavior. They are not physical-device,
Widevine-license-server, power, thermal or 8/24-hour soak evidence.

## External capability boundaries

- Dolby Vision/Atmos claims require licensed components, advertised hardware support and device
  certification. YCore only preserves secure routing and safe fallback.
- BD-J and licensed optical-disc navigation depend on external runtimes. Unsupported menu commands
  remain explicit instead of being simulated.
- A backend may be enabled only when its bundled build, license notice, native symbols and ABI/page
  size checks pass the release workflow.
