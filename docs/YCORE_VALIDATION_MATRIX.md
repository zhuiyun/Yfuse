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

- Containers: MP4/MOV, MKV, MPEG-TS, WebM, AVI, FLV, ISO and BDMV. DVD is an explicit
  unsupported-route/fallback assertion until YCore owns a verified DVD navigation runtime.
- Video: H.264 8/10-bit, HEVC Main/Main10, VP9, AV1, MPEG-2, ProRes 10/12-bit and VC-1 where available.
- Dynamic range: SDR, HDR10, HDR10+, HLG and every Dolby Vision profile the device advertises.
- Audio: AAC, MP3, FLAC, Opus, AC-3, E-AC3/JOC, DTS variants, TrueHD/Atmos and PCM 16/24-bit.
- Subtitles: SRT/WebVTT, ASS/SSA effects, PGS, VobSub and DVB; single, dual and offset tracks.
- UHD Blu-ray: local ISO, filesystem BDMV, persisted SAF BDMV tree and server-resolved M2TS/TS
  main-feature streams; HDR10/HDR10+/HLG, Dolby Vision P7/P8 where legally available, TrueHD/Atmos,
  DTS-HD and PGS.
- Optical navigation: one disc with authored edition names, one count-only disc, one explicit MPLS
  title hint, and chapters with timestamps; include direct non-adjacent title/chapter selection.
- Remote raw ISO transport: a range-capable authenticated origin, an origin that ignores ranges, a
  redirecting origin, EOF/416, expiring credentials and at least one 80–100+ GiB image.
- BDMV VFS: a normal filesystem tree, a tree URI whose selected node is the disc root, a tree URI whose
  selected node is `BDMV`, and a malformed/traversal corpus that must never escape the selected root.
- Large media: 4 GiB+ and 100 GiB+ samples must cover random seek, resume and EOF without 32-bit
  offset truncation. Include MOV/ProRes and Blu-ray image/main-feature cases when available.
- Faults: truncated manifests, corrupt timestamps, missing tracks, slow origin, 401/403/404/5xx,
  discontinuities and random seek/track/subtitle operations.

Every sample has a redacted manifest entry containing capability signature, expected route, expected
output and allowed fallback. Media URLs, tokens, account ids and server ids must never be committed.

### UHD Blu-ray route gates

- A valid server-resolved `.m2ts` / `.mts` / `.ts` main feature must remain direct-stream playback;
  the original MediaSource being ISO/BDMV is not by itself a reason to start server ffmpeg.
- Native raw ISO and BDMV are **binary capabilities**, not source-code assumptions. The exact AAR
  shipped in the release must come from the Yfuse libbluray build lane (or equivalent), have a pinned
  SHA-256, prove mpv was configured with `HAVE_LIBBLURAY=1`, and contain the embedded
  `dev.yfuse.mpv.YfuseMpvCapabilities` marker. The stock upstream v1.0.0 AAR does not satisfy this gate.
- Runtime capability detection must come from the installed AAR marker. A current full optical build
  must prove `REMOTE_RAW_BLURAY=true`, `BDMV_VFS=true`, `HDMV_MENU=true` and `BDJ=false`; missing/newer
  marker fields are treated as unsupported rather than guessed.
- The AAR verifier must find both `YfuseBluRayRegistry` and `YfuseBdmvRegistry`, both JNI symbol sets,
  both `yfusebd` and `yfusebdmv` stream protocols, AArch64 ELF output and PT_LOAD alignment of at least
  16 KiB for every bundled ARM64 shared library.
- Restoring the stock mpv AAR must remove custom provenance sidecars; stale metadata may never make a
  stock binary appear libbluray-capable.
- A known local Blu-ray/BDMV source without libbluray must fail fast instead of entering mpv and timing
  out. Generic ISO must remain unclassified until the bounded image inspector knows Blu-ray vs DVD.
- A `content://` Blu-ray ISO additionally requires the random-block/JNI bridge. A `content://` BDMV
  tree requires YCore's `bd_open_files` JNI VFS (or `BDMV_VFS` on the compatibility runtime); an
  older native binary containing only libbluray is not enough.
- Local Blu-ray ISO must start on the selected main feature and allow direct non-adjacent title/chapter
  navigation. Extracted BDMV must expose equivalent navigation/menu state through the same isolated
  HDMV provider contract.
- Rich optical metadata is optional: authored title/chapter names, ids, default flags, explicit MPLS
  hints and chapter timestamps must survive when present, while count-only backends remain usable.
- MPLS metadata may be surfaced only from an explicit `.mpls` / `MPLS/00001`-shaped hint. Arbitrary
  numbers in authored titles must not be guessed into playlist ids.
- During an engine handover, an outgoing navigation owner must not clear or receive commands intended
  for the newer active navigation backend. Replacing a backend for the same owner must close the old
  backend exactly once.

### Local BDMV VFS gates

The BDMV bridge is read-only and maps libbluray `bd_open_files(open_dir, open_file)` to Android. Before
shipping the route, all of the following must pass:

- filesystem selection accepts a disc root containing `BDMV/`, the `BDMV` directory itself, or an
  authored `index.bdmv` / `MovieObject.bdmv` selection and resolves them to one disc root;
- persisted SAF tree selection works both when the tree root is the disc root and when the selected
  tree itself is `BDMV`;
- absolute paths, NUL, `.` and `..` components are rejected before I/O; canonical filesystem children
  must remain underneath the selected root so symlinks cannot escape the sandbox;
- VFS file handles support read/tell/SEEK_SET/SEEK_CUR/SEEK_END with 64-bit positions and close exactly
  once; directory handles enumerate authored names and close exactly once;
- native `BD_FILE_H` and `BD_DIR_H` callbacks must compile against the pinned public libbluray 1.4.1
  filesystem ABI and propagate EOF/failure without native memory or JNI-global-reference leaks;
- BDMV navigation/menu failure remains optional: a provider failure clears interactive state but may
  not crash or tear down ordinary main-feature video playback;
- the source registry may contain no raw filesystem path, access token or account id in diagnostics.

### Remote raw ISO transport gates

`AndroidTransportDiscBlockSource` is the pure-YCore transport contract for the libbluray
`bd_open_stream` bridge. It accepts HTTP(S), WebDAV(S), and SMB through the same `YMediaTransport`
random-access boundary; the older `HttpRangeDiscBlockSource` belongs only to the retained legacy
route. Before native remote ISO is release-enabled, the following must pass:

- logical UDF blocks are exactly 2048 bytes and LBA-to-byte conversion uses 64-bit offsets; test at
  least one range whose start is above 4 GiB and one real image above 80 GiB;
- HTTP/WebDAV random reads receive `206 Partial Content` with a matching `Content-Range`; ordinary
  `200 OK` is a hard failure so Yfuse never turns a one-block request into a whole-image download.
  SMB must expose the equivalent exact random-access contract;
- requests force `Accept-Encoding: identity` and `Cache-Control: no-transform`; a compressed/rewritten
  range response is rejected because byte offsets are no longer trustworthy;
- redirects are not followed while authorization headers are attached. A redirecting origin must fail
  locally rather than leak credentials to the redirect target;
- authentication headers are resolved per request so a refreshed token can be used without rebuilding
  the native disc session; logs/diagnostics may contain neither the URL nor token;
- HTTP `416` with `Content-Range: bytes */N` is accepted as EOF and records the total image length;
- the reader must never perform blocking range I/O on Android's main thread and must become inert after
  close;
- the pure-YCore reader uses 256 KiB aligned read-ahead with a 2 MiB LRU ceiling, preserves exact
  requested blocks, bounds repeated zero-progress reads, and retains no data after session close or
  source switch;
- transport unit tests are necessary but not sufficient: release additionally requires the actual JNI
  `bd_open_stream` callback, libbluray title/seek/event integration, cancellation and physical-device
  seeks across the image.

### Startup / large-source gates

- A fixed eight-second mpv `FILE_LOADED` deadline is not acceptable for optical images or very large
  MOV/ProRes. Runtime must use the source-aware adaptive/stall policy rather than only defining it in
  tests/helpers.
- Optical-disc startup receives a long initial grace period and may continue while real load/cache
  progress is observed, but a hard upper bound still prevents a dead backend from hanging forever.
- MOV/ProRes 10/12-bit startup has a separate large-source window; the 100 GiB+ validation sample must
  start, random-seek, resume and reach EOF without 32-bit truncation or a false watchdog failure.
- A stale attempt, released engine or already-loaded media must never fire a later watchdog callback.
- Stall and hard-timeout diagnostics report bounded policy/decision metadata only; media URL/token may
  never be logged.

### HDR, audio, subtitle and menu gates

- PGS may change the local backend to the native subtitle renderer, but must not change a valid
  direct-stream URL into a server transcode.
- E-AC3 JOC is reported as Atmos passthrough only when the advancing AudioTrack's active routed
  device exposes the exact JOC encoding. TrueHD capability proves only a carrier until independent
  receiver evidence proves the Atmos object extension. Speaker/headphone PCM spatialization is
  reported as a separate Atmos-source presentation mode and never as encoded passthrough.
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
  predictive-back behavior must resume.
- Interactive Graphics and movie PGS stay separate planes; clearing/closing a menu must not clear the
  selected movie subtitle renderer or leave stale menu pixels on screen.
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
| Soak | 8-hour continuous single-item and 24-hour queue runs without leak, ANR or thermal runaway |

The instrumented soak lane is opt-in and never pretends that CI/emulator time is device evidence.
Push a legally usable sample to the device, then run the same test once for each required duration:

```bash
# 8-hour continuous single-item lane
./gradlew :composeApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yfuse.core2.android.YCoreMediaSuiteInstrumentedTest#configured_long_running_soak_keeps_output_and_health_stable \
  -Pandroid.testInstrumentationRunnerArguments.ycoreSoakMedia=/sdcard/Download/ycore-soak.mkv \
  -Pandroid.testInstrumentationRunnerArguments.ycoreSoakDurationMinutes=480

# 24-hour queue/handover lane
./gradlew :composeApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yfuse.core2.android.YCoreMediaSuiteInstrumentedTest#configured_long_running_soak_keeps_output_and_health_stable \
  -Pandroid.testInstrumentationRunnerArguments.ycoreSoakMedia=/sdcard/Download/ycore-soak.mkv \
  -Pandroid.testInstrumentationRunnerArguments.ycoreSoakDurationMinutes=1440 \
  -Pandroid.testInstrumentationRunnerArguments.ycoreSoakQueue=true
```

The runner samples PSS, thermal status, battery delta, dropped frames, decoder failures and A/V drift,
and streams a redacted JSON observation through instrumentation status output. Archive that output
with the device/build label; do not commit the media path or credentials.

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
- DVD navigation remains outside YCore until a separately pinned and verified DVD runtime exists;
  an unsupported DVD must fail closed or use the explicit compatibility route, never masquerade as
  Blu-ray support.
- A backend may be enabled only when its bundled build, license notice, native symbols, capability
  marker, registry ABI and ABI/page-size checks pass the release workflow.
