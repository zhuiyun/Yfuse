# Playback fixes — 1.0.96 (258)

Base: cloud `master` at `508db23578aa54383b9a36657dbc93b81fa9b2ce`, the 1.0.95 (257) package.
The owner asked to package both fixes below as one 1.0.96 (258) delivery.

## Offline playback under YCore Native only (`0229e404`)

- The 1.0.95 (257) diagnostics exported on September 30 at 13:01:56 (Asia/Shanghai) show four starts, at 13:00:55, 13:00:57, 13:01:11 and 13:01:13. Each was refused within about 50 ms with 「YCore Native 缺少片源格式元数据」, on engine `Unavailable`, with no audio or video output.
- The logged start came from the download list's launcher: `Player activity launched`, one item, no PlaybackInfo. Its source record had no container, codec, size or tracks. An online start in the same session played.
- The phone and TV download lists launch a finished download as a `PlayerMediaItem` with the local file and no versions, because a download has no server MediaSource. With YCore Native only (the phone setting, and every TV build), the native source gate refused every item without a version.
- The gate now admits `file`, `content` and `android.resource` sources without metadata. YCore reads their container and codecs from the bytes (MediaExtractor, then its FFmpeg probe) and fails closed there, without a legacy engine.
- A remote source without metadata is still refused. Every other gate check is unchanged.

## Sidecar subtitle addresses (`b704959c`)

- The September 30 diagnostics from 1.0.94 (256) showed YCore Native refusing two starts over an external subtitle. 1.0.95 stopped sidecars from blocking video; reviewing the same path showed the sidecar address itself was unusable. Emby and Jellyfin return `DeliveryUrl` relative to the server (`/Videos/…/Subtitles/3/0/Stream.srt`), and it reached YCore, MPV and Exo unchanged.
- `toPlayerMediaVersions` completes and authenticates a relative sidecar address against the saved server, as it already did for the negotiated stream URLs. Absolute provider URLs (Plex, a CDN) stay unmodified.
- A sidecar the server marks `IsExternalUrl` whose value is a path on the server's disk (beside a `.strm` file) is requested from the subtitle endpoint by stream index.
- A sidecar is parsed as the format the endpoint sends. Under this device profile Emby converts an ASS sidecar to SRT while the track codec still reads ASS.

## Verification

- Previous delivery: packaging run [36660691976](https://github.com/zhuiyun/Yfuse/actions/runs/36660691976) (#138) on `508db235`. Its log records `Yfuse-257-1.0.95.apk`, `1.0.95`, `257`, 29,818,223 bytes, `PUBLISH_UPDATE=false`, and a passing APK metadata and signing-certificate step. No packaging run followed it. The 1.0.95 (257) diagnostics above came from that build on the owner's device. Provenance and hash are in `verification.json`.
- The branch `ccr-0704ea5f-45t6a9` prepared 1.0.96 (258) for the sidecar fix alone and was never packaged. Both fixes now share that version.
- New version configuration and release notes: `1.0.96 (258)`, with both fixes listed and historical notes retained.
- Offline fix: Kotlin 2.4.20 on the JVM ran `Core2NativeBaselineTest` and `YCoreNativeReadinessTest`, 15 tests, all passing. The new gate case fails on the previous gate (`scheme file expected null, but was: MissingMetadata`). The new `AndroidCore2TrialTest` case needs the Android build.
- Sidecar fix: a Kotlin 2.4.20 JVM harness built from the real `EmbyStream`, `MediaVersion`, DTO and `PlayerStore` sources passed 10 tests, including the 6 new `SidecarSubtitleUrlTest` cases, 4 of which fail on the pre-fix code.
- `python scripts/release_metadata.py`, `python scripts/test_release_metadata.py`, `python scripts/verify-module-boundaries.py`, `git diff --check`, and ktlint 1.3.1 on the changed Kotlin files: passed.
- Local Android execution is unavailable: this workspace has no Android SDK and `dl.google.com` is blocked.
- PR #209 head `47929f95`: all 8 checks passed, including Android quality gates run [36691372028](https://github.com/zhuiyun/Yfuse/actions/runs/36691372028) (unit tests, instrumented-test compilation, Android lint, R8 release package at 29,817,959 bytes within the 30,000,000-byte budget, ktlint and dependency locks), the TV APK, the YCore native runtime, CodeQL, the release scripts and the dependency gate.
- On September 30 at 17:15 (Asia/Shanghai) the release owner explicitly confirmed use and distribution of the existing MDK SDK for this package-only 1.0.96 (258) delivery. `.github/mdk-distribution-approval.json` records it without changing the SDK checksum or the approval scope.
- Pending: Android quality gates on the merge commit and production signing.

Package-only delivery is intended. Do not publish an application update as part of this build.
