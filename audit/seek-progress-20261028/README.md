# Seek, progress consistency and player clock repair

Base: `60f7e0e08c5388d80ecf471e1e5faa4d6a20619f` (1.7.8 / 330), matching the supplied diagnostics.

## Changes

- NativeDirect reuses retained compressed packets only when every required track covers the target and a preceding video sync sample remains. Decode order (including reordered timestamps) is preserved. Packet epochs reject samples held before a seek without dropping an in-flight tail read. Cache misses still use the extractor.
- Platform seek has a 10-second aggregate budget. Expiry aborts further reads and reports a typed Container/Seek failure to the existing enhanced-demux recovery policy. A release barrier prevents reopening while a previous native call still owns the extractor. Other synchronous owner operations are bounded at 30 seconds; retirement waits at most 2 seconds per attempt and cannot claim success if still blocked.
- Retry/release permanently cancel old source admission rather than just one range. A cancelled source is never reused for a decode-side retry. Restart logging now describes a request, not successful output.
- Remote buffered position uses parsed timestamps available for every required media track, not byte/bitrate estimates. Cache reuse still requires a usable sync sample; a displayed buffer does not promise zero decode latency.
- Detail and playback-detail metadata returns reproject current exact-item progress after cache/network completion. Detail alias fallback cannot replace an existing concrete item record or borrow another server item's progress. Playback launch supplies the exact item ID to authoritative position lookup.
- Player time readouts use the existing RollingNumber with throttled updates, retaining the accessibility/reduced-motion behavior.

## Verification

Host regression: 44 queue/recovery tests + 12 transport tests + 12 progress tests passed. The queue and transport runners compile whole production files with explicit Android/JNI type fixtures. The progress runner compiles the real store, serializer, conflict resolver and progress projections with in-memory settings and metadata/account fixtures. These are not full application or device tests.

- `python audit/seek-progress-20261028/run-demux.py`
- `python audit/seek-progress-20261028/run-host.py --label seek-transport`
- `python audit/seek-progress-20261028/run-progress.py`
- `python scripts/check_function_size.py`
- `git diff --check`

Dependency jars were reused from the existing local verified audit caches. Place Kotlin 2.4.21, JUnit 4.13.2, coroutines 1.11.0 and the serialization dependencies in `deps`; progress tests additionally need the Kotlin serialization compiler plugin and serialization-json in `progress-deps` (or set YFUSE_PROGRESS_TEST_DEPS). Local cache links and classes are ignored. Dependency checksums are recorded beside the results.

Ktlint 1.3.1 passed for changed production/test Kotlin. Full Gradle execution is blocked: the Gradle 9.8.1 distribution is absent and services.gradle.org is unreachable from this runtime. No version bump, APK, signing, installation or remote push was performed.

## Remaining device checks

- Play the reported Dolby Vision MKV; seek inside retained keyframe coverage and verify native_direct_seek_resolved buffered=true without a platform reposition.
- Resume/seek to 26:25 outside the queue; if platform seek stalls, verify enhanced demux starts at the target after the old owner retires. Confirm audio/video resume and Dolby output on the affected OPPO device.
- Rapid repeated seeks, paused preview, backward seeks without a cached sync sample, exit during a blocked seek, and retry must not reuse the destroyed Surface or overlap old/new extractor ownership.
- Sync 5 minutes to 22 minutes; reopen detail and launch while cached metadata exists, then test explicit restart/completion, source switch and account switch. Network sync still takes time; this fix does not claim instantaneous remote data.
- Verify clock animation during normal playback and scrubbing, including minute/hour carry, reduced motion and hidden controls.
