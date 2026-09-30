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
- Kotlin tests, ktlint and release compilation: not executed. Gradle 9.8.0 download failed with `Network is unreachable`; the local workspace has no Android SDK. Cloud verification and production signing are still required.

Package-only delivery is intended. Do not publish an application update as part of this build.
