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
- Optical navigation: one disc with authored edition names, one count-only disc, one explicit MPLS
  title hint, and chapters with timestamps; include direct non-adjacent title/chapter selection.
- Remote raw ISO transport: a range-capable authenticated origin, an origin that ignores ranges, a
  redirecting origin, EOF/416, expiring credentials and at least one 80–100+ GiB image.
- Large media: 4 GiB+ and 100 GiB+ samples must cover random seek, resume and EOF without 32-bit
  offset truncation. Include MOV/ProRes and Blu-ray image/main-feature cases when available.
- Faults: truncated manifests, corrupt timestamps, missing tracks, slow origin, 401/403/404/5xx,
  discontinuities and random seek/track/subtitle operations.

Every sample has a redacted manifest entry containing capability signature, expected route, expected
output and allowed fallback. Media URLs, tokens, account ids and server ids must never be committed.

### UHD Blu-ray route gates

- A valid server-resolved `.m2ts` / `.mts` / `.ts` main feature must remain direct-stream playback;
  the original MediaSource being ISO/BDMV is not by itself a reason to start server ffmpeg.
- A raw remote ISO/BDMV without a resolved linear stream must remain on the server main-feature
  fallback until the libbluray `bd_open_stream` JNI/session bridge is actually present and validated.
  The existence of an HTTP range reader alone must not silently enable the route.
- Native local Blu-ray is a **binary capability**, not a source-code assumption. The exact AAR shipped
  in the release must come from the Yfuse libbluray build lane (or equivalent), have a pinned SHA-256,
  prove mpv was configured with `HAVE_LIBBLURAY=1`, and contain the embedded
  `dev.yfuse.mpv.YfuseMpvCapabilities` marker. The stock upstream v1.0.0 AAR does not satisfy this
  gate merely because YCore can construct a `bd://` URL.
- Runtime detection must be derived from the installed AAR marker. If the marker is absent, a known
  local Blu-ray/BDMV source must fail fast as a missing native capability rather than entering mpv and
  timing out. Generic ISO must stay unclassified until the bounded image inspector knows Blu-ray vs DVD.
- Restoring the stock mpv AAR must remove custom provenance sidecars; stale metadata may never make a
  stock binary appear libbluray-capable.
- Once the binary gate passes, local Blu-ray ISO/BDMV must start from `bd://longest`; the first rendered
  title must match the longest playlist unless the user explicitly selects another title.
- Rich optical metadata is optional: authored `edition-list` / `chapter-list` names, ids, default flags
  and chapter timestamps must survive when present, while count-only `editions` / `chapters` must still
  produce usable navigation rows.
- The playback UI must directly select any exposed title/edition or chapter. Reaching title N may not
  require N-1 repeated “next” commands, and a selection must update against the same active engine.
- MPLS metadata may be surfaced only from an explicit `.mpls` / `MPLS/00001`-shaped hint. Arbitrary
  numbers in authored titles must not be guessed into playlist ids.
- During an engine handover, an outgoing navigation owner must not clear or receive commands intended
  for the newer active navigation backend. Replacing a backend for the same owner must close the old
  backend exactly once.

### Remote raw ISO transport gates

`HttpRangeDiscBlockSource` is the transport contract for a future libbluray `bd_open_stream` bridge.
It does not by itself change playback policy. Before the bridge can be enabled, the following must pass:

- logical UDF blocks are exactly 2048 bytes and LBA-to-byte conversion uses 64-bit offsets; test at
  least one range whose start is above 4 GiB and one real image above 80 GiB;
- every random read receives HTTP `206 Partial Content` with a matching `Content-Range`; ordinary
  `200 OK` is a hard failure so Yfuse never turns a one-block request into a whole-image download;
- requests force `Accept-Encoding: identity` and `Cache-Control: no-transform`; a compressed/rewritten
  range response is rejected because byte offsets are no longer trustworthy;
- redirects are not followed while authorization headers are attached. A redirecting origin must fail
  locally rather than leak credentials to the redirect target;
- authentication headers are resolved per request so a refreshed token can be used without rebuilding
  the native disc session; logs/diagnostics may contain neither the URL nor token;
- HTTP `416` with `Content-Range: bytes */N` is accepted as EOF and records the total image length;
- the reader must never perform blocking range I/O on Android's main thread and must become inert after
  close;
- transport unit tests are necessary but not sufficient: the first direct-remote-ISO release also needs
  the real JNI `bd_open_stream` callback, libbluray title/seek/event integration, cancellation and
  physical-device seeks across the image.

### HDR, audio, subtitle and menu gates

- PGS may change the local backend to the native subtitle renderer, but must not change a valid
  direct-stream URL into a server transcode.
- TrueHD/Atmos or DTS-HD is reported as passthrough only when the active Android route proves encoded
  output; speaker/Bluetooth fallback to PCM is a passing outcome.
- Dolby Vision source metadata must preserve RPU/EL/BL presence flags. A P7 source with EL present is
  a dual-layer source, but `ElPresentFlag` alone is not accepted as evidence of MEL/FEL type or FEL
  composition.
- Dolby Vision P7 FEL is `NotMeasured` unless a physical-device trace proves the enhancement layer is
  being composed. Base-layer playback is not sufficient evidence for an FEL-support claim.
- An HDMV provider must report lifecycle and runtime capability independently of video decode. Any
  native exception/failed command must mark only that optional provider failed, clear active-menu
  state and leave main-feature playback alive.
- Asynchronous native menu/title changes must push a navigation revision; UI/platform input must not
  poll a native handle or stay stale after overlay/menu events.
- Android D-pad/enter/system-back events may be consumed only while a provider reports a ready
  interactive runtime **and** an active menu. When the provider closes/fails, normal Activity key and
  back behavior must resume.
- BD-J remains unsupported until a separately verified Java runtime/provider exists. Building
  libbluray with `bdj_jar=disabled` is never accepted as BD-J evidence.

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
Widevine-license-server, native libbluray build, power, thermal or 8/24-hour soak evidence.

## External capability boundaries

- Dolby Vision/Atmos claims require licensed components, advertised hardware support and device
  certification. YCore only preserves secure routing and safe fallback.
- HDMV navigation requires a real libbluray-backed provider; BD-J additionally requires a verified
  Java runtime. Unsupported menu commands remain explicit instead of being simulated.
- A backend may be enabled only when its bundled build, license notice, native symbols, capability
  marker and ABI/page-size checks pass the release workflow.
