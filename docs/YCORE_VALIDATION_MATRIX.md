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

- Containers: MP4/MOV, MKV, MPEG-TS, WebM, AVI, FLV, ISO, DVD and BDMV; include MOV files above
  4 GiB and at least one 100+ GiB sample to exercise 64-bit offset/range-seek behavior.
- Video: H.264 8/10-bit, HEVC Main/Main10, VP9, AV1, MPEG-2 and VC-1 where available; ProRes must
  include 10-bit and 12-bit samples routed through the native FFmpeg software-decoder path.
- Dynamic range: SDR, HDR10, HDR10+, HLG and every Dolby Vision profile the device advertises.
- Audio: AAC, MP3, FLAC, Opus, AC-3, E-AC3/JOC, DTS variants, TrueHD/Atmos and PCM 16/24-bit,
  including `pcm_s24le` paired with ProRes MOV.
- Subtitles: SRT/WebVTT, ASS/SSA effects, PGS, VobSub and DVB; single, dual and offset tracks.
- Faults: truncated manifests, corrupt timestamps, missing tracks, slow origin, 401/403/404/5xx,
  discontinuities and random seek/track/subtitle operations.

Every sample has a redacted manifest entry containing capability signature, expected route, expected
output and allowed fallback. Media URLs, tokens, account ids and server ids must never be committed.

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
