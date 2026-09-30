# Playback startup repair — 1.0.95 (257)

Base: cloud `master` at `f9e4d87cc3b4c7ec22bdee3f70a61fa426e1795f`.
The separate September 30 integration checkout is preserved; this repair does not incorporate its unpublished merge.

## Evidence and changes

- September 30, 08:28:29 and 08:28:52, Asia/Shanghai: playback negotiation succeeded, but the native source gate rejected the entire item because one external subtitle was unsupported. Neither session produced audio/video output.
- Remove external sidecars from video-source admission. Preserve the subtitle catalog and track ordinals; the existing lazy subtitle session contains load errors to the selected track and lets other subtitles load. Add scheme/format-only failure diagnostics.
- The rejected-start placeholder reported `libmpv`, while attachment logging called it `Exo` and the report binding called it `YCore2Native`. All actual-binding diagnostics now identify it as `Unavailable`, with no claimed fallback chain.
- The second source's optional preflight also returned HTTP 302 as `InvalidRange`. Valid redirects already have bounded follow logic. Missing/invalid Location, redirect-limit and cleartext-policy failures now retain safe typed reasons, remain network/source errors, and are not blindly retried. The original Location was not logged, so this change does not prove the original second-server redirect is playable.

## Verification

- Read the previous production APK's binary manifest: `com.yfuse`, `1.0.94`, version code `256`. Artifact provenance and hash are in `verification.json`.
- New version configuration and release notes: `1.0.95 (257)`; historical notes retained.
- `git diff --check`: passed.
- `python scripts/test_release_metadata.py`: 4 tests passed.
- `python scripts/verify-module-boundaries.py`: passed.
- Added regression cases for unsupported sidecars in the current/queued item, subtitle failure isolation, rejected-engine labels, malformed redirects, and redirect loops; updated the non-retry policy test.
- Local Android execution was unavailable because the Gradle download was blocked and this workspace has no Android SDK. Cloud run [36657529338](https://github.com/zhuiyun/Yfuse/actions/runs/36657529338) subsequently passed all Android quality gates on repair commit `228ed1d0cf533bc05e56c51c0be2004cda9d0ffe`: compilation, unit tests, ktlint, Android lint, R8 release build, package checks and size budget. TV, CodeQL and YCore native runtime checks also passed.
- Read the actual CI preview APK manifest: `com.yfuse`, `1.0.95 (257)`, 29,817,927 bytes. Its certificate is Android Debug, so it is not the production deliverable. Production signing remains pending.
- At 2026-09-30 10:35:30 Asia/Shanghai, the release owner explicitly confirmed use and distribution of the existing MDK SDK for this package-only 1.0.95 (257) delivery. The version-specific approval record is updated without changing the SDK checksum or approval scope.

Package-only delivery is intended. Do not publish an application update as part of this build.
