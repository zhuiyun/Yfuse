# Sidecar subtitle addresses — 1.0.96 (258)

Base: cloud `master` at `508db23578aa54383b9a36657dbc93b81fa9b2ce`, the 1.0.95 (257) package.

## Evidence and changes

- The September 30 diagnostics from 1.0.94 (256) showed YCore Native refusing two starts over an external subtitle. 1.0.95 stopped sidecars from blocking video; reviewing the same path showed the sidecar address itself was unusable. Emby and Jellyfin return `DeliveryUrl` relative to the server (`/Videos/…/Subtitles/3/0/Stream.srt`), and it reached YCore, MPV and Exo unchanged.
- `toPlayerMediaVersions` completes and authenticates a relative sidecar address against the saved server, as it already did for the negotiated stream URLs. Absolute provider URLs (Plex, a CDN) stay unmodified.
- A sidecar the server marks `IsExternalUrl` whose value is a path on the server's disk (beside a `.strm` file) is requested from the subtitle endpoint by stream index.
- A sidecar is parsed as the format the endpoint sends. Under this device profile Emby converts an ASS sidecar to SRT while the track codec still reads ASS.

## Verification

- Previous delivery: packaging run [36660691976](https://github.com/zhuiyun/Yfuse/actions/runs/36660691976) on `508db235` built `Yfuse-257-1.0.95.apk` and its verification step passed: `aapt` badging `com.yfuse`, `1.0.95`, `257`; `apksigner` matched the pinned production certificate; not published. This workspace cannot download Actions artifacts (egress policy), so that run's verification log is the evidence. Provenance and hash are in `verification.json`.
- New version configuration and release notes: `1.0.96 (258)`; historical notes retained.
- `python scripts/release_metadata.py` and `python scripts/test_release_metadata.py` (4 tests): passed.
- Local Android execution is unavailable: this workspace has no Android SDK and `dl.google.com` is blocked. The changed Kotlin compiled with Kotlin 2.4.20 in a standalone JVM harness built from the real `EmbyStream`, `MediaVersion`, DTO and `PlayerStore` sources: 10 tests passed, including the 6 new `SidecarSubtitleUrlTest` cases, 4 of which fail on the pre-fix code. ktlint 1.3.1 reported no violations in the changed files.
- Pending: Android quality gates on the merge commit, the release owner's MDK confirmation for this package-only 1.0.96 (258) delivery, and production signing.

Package-only delivery is intended. Do not publish an application update as part of this build.
