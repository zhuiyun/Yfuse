# YCore playback architecture

YCore is an adaptive orchestration kernel. It combines the efficient Android/Media3 path with the
format coverage of mpv/FFmpeg and the independent MDK fallback without exposing those backends to
the shared controls or settings UI.

## Boundaries

| Boundary | Responsibility | Must not contain |
| --- | --- | --- |
| `core/playback/PlaybackPlanner` | Pure route selection | Android, Compose, network calls |
| `PlaybackMediaProbeService` | Bounded MediaExtractor + FFmpeg inspection | Persistence, UI state |
| `PlaybackDiscPolicy` + `PlaybackDiscImageInspector` | Disc classification and pure ISO marker inspection | Android I/O, backend commands |
| `PlaybackAdaptiveNetworkController` | Throughput/buffer/rebuffer decisions | URL construction, UI |
| `PlaybackNetworkRecoveryController` | Offline/online transition and one-shot resume intent | Android callbacks, backend construction |
| `PlaybackOfflineLicenseManager` | Widevine acquire/query/renew/release lifecycle | UI state, media identity persistence |
| `YCorePlaybackSession` | Health, failure and benchmark feedback | Compose lifecycle |
| `PlaybackRuntimeFaultDetector` | First-frame, silent-output and stalled-position detection | UI, backend construction |
| `PlaybackReleaseValidation` | Numeric release-gate and percentile evaluation | Device automation, release claims |
| `PlaybackRuntimeEnvironmentProvider` | Battery saver and thermal pressure | Route policy |
| `feature/player/YCorePlayerRuntime` | Compose lifecycle adapter | Thresholds and engine policy |
| `VideoEngine` | Backend-neutral playback contract | Concrete player types |

Android adapters live in `androidMain`; policy and state machines live in `commonMain`. New probes
or backends implement an interface instead of adding conditions to `PlayerRoot`.

## Route order

1. Use server PlaybackInfo for a zero-delay initial plan.
2. Start a bounded Android `MediaExtractor` probe in parallel with playback.
3. Escalate failed, incomplete and native-first sources to a headless libmpv/FFmpeg probe.
4. Reconcile only when deeper facts change the required backend, decoder, DRM or transcode path.
5. Prefer platform hardware decode for normal, DRM and power-sensitive playback.
6. Prefer mpv for exotic containers, complex subtitles, unsupported audio and local disc images.
7. Ask the server to parse remote ISO/DVD/Blu-ray/BDMV sources and return H.264/AAC.
8. Fall through the planned backend order, then alternate versions and servers; never cycle.

The engine selector has an explicit `Auto` mode and three backend locks. `Auto` permits the route
order above. A lock produces a one-entry backend order and ignores learned performance/failure
reranking. The only override is the secure platform path for DRM and Dolby-only output; a backend
lock is never allowed to weaken the media security contract.

## Secure and disc playback

- `PlaybackDrmConfiguration` carries Widevine, ClearKey or PlayReady license parameters to Media3.
- License URIs, headers and offline key-set ids never enter YCore diagnostics or learning keys.
- Offline Widevine licenses support acquisition from DASH/HLS manifests or supplied PSSH, duration
  queries, seven-day renewal decisions, renewal, server release and explicit local deletion. Key-set
  bytes are encrypted with an Android Keystore-backed `SecureStore`; only expiry metadata is kept in
  ordinary settings, and temporary byte arrays are cleared after each operation.
- DRM failures cannot cycle into native backends that cannot satisfy the secure decoder contract.
- mpv exposes DVD/Blu-ray titles and chapters through `VideoEngine`; menu commands stay behind
  the backend-neutral contract and report unsupported because bundled mpv does not implement DVD menus.
- Local DVD/BDMV paths are converted to native `dvd://`/`bd://` sources; remote discs still prefer
  server main-feature parsing.
- A local `file://` ISO is inspected through an eight-MiB read ceiling. Recognized UDF/ISO9660
  directory markers classify DVD versus Blu-ray, and the completed deep probe rebuilds mpv with
  the corresponding native disc device. URI hashes and a 12-entry memory cache prevent path
  persistence. `content://` sources remain raw/server-routed because libdvdnav/libbluray require a
  real device path.
- BD-J and licensed Dolby decoding remain external runtime capabilities, not claims made by YCore.

## Adaptive feedback

- Engine-local decoder, renderer and container failures are remembered for seven days.
- Network and authentication failures never blacklist a decoder.
- A binding is assessed only after 30 seconds of rendered playback.
- Sustained severe frame loss records an engine-local renderer failure.
- A two-second active-playback observer detects a missing first frame, media progress without verified
  audio/video output, and a non-buffering position stall. In `Auto` it records the scoped failure,
  hands over to the next planned backend, then requests server transcode when no backend remains.
  Paused, buffering, ended, cast-controlled and explicitly locked sessions do not auto-switch.
- Completed sessions update a 30-day rolling startup/rebuffer/drop baseline.
- At least two samples are required before performance history can reorder equivalent engines.
- Network adaptation combines rebuffer counts, EWMA throughput and forward-buffer pressure before
  lowering a server quality cap; sustained 1.75x throughput headroom and a 25-second forward buffer
  recover one quality step at a time. A three-minute upgrade cooldown prevents oscillation, and an
  automatic change never overwrites the user's persisted quality ceiling. Zero/unknown bandwidth
  and local files never trigger adaptation.
- Direct HLS and DASH sources use Media3 adaptive track selection. User/network quality changes update
  the in-manifest bitrate and resolution ceiling without rebuilding playback; fixed streams and
  server-transcoded URLs continue through the existing handover path.
- Android default-network transitions feed one backend-neutral recovery state machine. A genuine
  offline-to-online transition retries Exo, mpv or MDK once at the backend's retained position while
  preserving an explicit pause or ended state.
- Exo samples video presentation time against the Media3 playback clock, and mpv reports its native
  `avsync` clock delta. Unsupported MDK measurement remains explicitly unknown.
- Backend, version, server and queue rebuilds carry one engine-neutral handover snapshot. Position,
  play/pause intent and speed are constructor inputs for the next backend; audio and subtitle tracks
  are restored by stable metadata because backend-local track ids cannot be reused.
- System power saver, low battery and severe thermal pressure can temporarily select the power
  saver plan. Compatibility mode survives low battery; severe thermal pressure always wins.
- When Android exposes battery current, the diagnostic panel adds device-wide measured watts to
  the route-based power estimate; charging and unsupported meters remain explicitly unknown.
- Users can clear local failure and performance learning from the media diagnostic page.
- Users may explicitly opt in to anonymous QoE sharing. Reports use fixed numeric buckets and coarse
  platform/media dimensions (including only an enum-level SoC vendor family, never model/board strings),
  contain no URL, media/server/account id, locale, IP or stable device id,
  and are sent over HTTPS without account authorization. The server persists daily aggregate counts
  only; disabling consent immediately deletes the bounded local outbox.

Device/corpus acceptance criteria and the external Dolby/BD-J boundaries are tracked in
[`YCORE_VALIDATION_MATRIX.md`](YCORE_VALIDATION_MATRIX.md).

## Privacy and resource limits

- Probe URIs are transient, redacted by `toString`, hashed for the in-memory cache and never logged.
- Persisted records contain only a capability signature, engine, counters and timestamps.
- Failure and performance stores are bounded to 96 records.
- The combined platform/native deep probe has a four-second budget and a 24-entry in-memory cache.
- ISO inspection reads at most eight MiB and keeps at most 12 hashed entries in memory.
- No media title, item id, server id, account, URL or access token is persisted by YCore.
- Offline key-set ids are the sole exception to ordinary YCore persistence policy and remain encrypted
  under their own Keystore namespace; license URLs and request headers are never persisted.
