# YCore playback architecture

YCore is an adaptive orchestration kernel. It combines the efficient Android/Media3 path with the
format coverage of mpv/FFmpeg and the independent MDK fallback without exposing those backends to
the shared controls or settings UI.

## Boundaries

| Boundary | Responsibility | Must not contain |
| --- | --- | --- |
| `core/playback/PlaybackPlanner` | Pure route selection | Android, Compose, network calls |
| `PlaybackMediaProbeService` | Bounded read-only media inspection | Persistence, UI state |
| `PlaybackDiscPolicy` | ISO/DVD/Blu-ray/BDMV classification and route | Backend commands |
| `YCorePlaybackSession` | Health, failure and benchmark feedback | Compose lifecycle |
| `PlaybackRuntimeEnvironmentProvider` | Battery saver and thermal pressure | Route policy |
| `feature/player/YCorePlayerRuntime` | Compose lifecycle adapter | Thresholds and engine policy |
| `VideoEngine` | Backend-neutral playback contract | Concrete player types |

Android adapters live in `androidMain`; policy and state machines live in `commonMain`. New probes
or backends implement an interface instead of adding conditions to `PlayerRoot`.

## Route order

1. Use server PlaybackInfo for a zero-delay initial plan.
2. Start a bounded Android `MediaExtractor` probe in parallel with playback.
3. Reconcile only when deeper facts change the required backend, decoder, DRM or transcode path.
4. Prefer platform hardware decode for normal, DRM and power-sensitive playback.
5. Prefer mpv for exotic containers, complex subtitles, unsupported audio and local disc images.
6. Ask the server to parse remote ISO/DVD/Blu-ray/BDMV sources and return H.264/AAC.
7. Fall through the planned backend order, then alternate versions and servers; never cycle.

## Adaptive feedback

- Engine-local decoder, renderer and container failures are remembered for seven days.
- Network and authentication failures never blacklist a decoder.
- A binding is assessed only after 30 seconds of rendered playback.
- Sustained severe frame loss records an engine-local renderer failure.
- Completed sessions update a 30-day rolling startup/rebuffer/drop baseline.
- At least two samples are required before performance history can reorder equivalent engines.
- System power saver, low battery and severe thermal pressure can temporarily select the power
  saver plan. Compatibility mode survives low battery; severe thermal pressure always wins.

## Privacy and resource limits

- Probe URIs are transient, redacted by `toString`, hashed for the in-memory cache and never logged.
- Persisted records contain only a capability signature, engine, counters and timestamps.
- Failure and performance stores are bounded to 96 records.
- Deep probes have a four-second budget and a 24-entry in-memory cache.
- No media title, item id, server id, account, URL or access token is persisted by YCore.
