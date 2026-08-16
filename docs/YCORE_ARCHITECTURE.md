# YCore playback architecture

YCore is an adaptive orchestration kernel. It combines the efficient Android/Media3 path with the
format coverage of mpv/FFmpeg and the independent MDK fallback without exposing those backends to
the shared controls or settings UI.

## Boundaries

| Boundary | Responsibility | Must not contain |
| --- | --- | --- |
| `core/playback/PlaybackPlanner` | Pure route selection | Android, Compose, network calls |
| `PlaybackMediaProbeService` | Bounded MediaExtractor + FFmpeg inspection | Persistence, UI state |
| `PlaybackDiscPolicy` | ISO/DVD/Blu-ray/BDMV classification and route | Backend commands |
| `PlaybackAdaptiveNetworkController` | Throughput/buffer/rebuffer decisions | URL construction, UI |
| `YCorePlaybackSession` | Health, failure and benchmark feedback | Compose lifecycle |
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

## Secure and disc playback

- `PlaybackDrmConfiguration` carries Widevine, ClearKey or PlayReady license parameters to Media3.
- License URIs, headers and offline key-set ids never enter YCore diagnostics or learning keys.
- DRM failures cannot cycle into native backends that cannot satisfy the secure decoder contract.
- mpv exposes DVD/Blu-ray titles and chapters through `VideoEngine`; menu commands stay behind
  the backend-neutral contract and report unsupported because bundled mpv does not implement DVD menus.
- Local DVD/BDMV paths are converted to native `dvd://`/`bd://` sources; remote discs still prefer
  server main-feature parsing.
- BD-J and licensed Dolby decoding remain external runtime capabilities, not claims made by YCore.

## Adaptive feedback

- Engine-local decoder, renderer and container failures are remembered for seven days.
- Network and authentication failures never blacklist a decoder.
- A binding is assessed only after 30 seconds of rendered playback.
- Sustained severe frame loss records an engine-local renderer failure.
- Completed sessions update a 30-day rolling startup/rebuffer/drop baseline.
- At least two samples are required before performance history can reorder equivalent engines.
- Network adaptation combines rebuffer counts, EWMA throughput and forward-buffer pressure before
  lowering a server quality cap; zero/unknown bandwidth and local files never trigger it.
- System power saver, low battery and severe thermal pressure can temporarily select the power
  saver plan. Compatibility mode survives low battery; severe thermal pressure always wins.
- When Android exposes battery current, the diagnostic panel adds device-wide measured watts to
  the route-based power estimate; charging and unsupported meters remain explicitly unknown.
- Users can clear local failure and performance learning from the media diagnostic page.

## Privacy and resource limits

- Probe URIs are transient, redacted by `toString`, hashed for the in-memory cache and never logged.
- Persisted records contain only a capability signature, engine, counters and timestamps.
- Failure and performance stores are bounded to 96 records.
- The combined platform/native deep probe has a four-second budget and a 24-entry in-memory cache.
- No media title, item id, server id, account, URL or access token is persisted by YCore.
