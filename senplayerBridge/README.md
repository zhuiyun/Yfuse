# SenPlayer experimental Android bridge

This module adapts the runtime from the user-supplied `SenPlayer.apk`, SHA-256
`45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c`.
It is an integration of supplied binaries, not a recovered or independently buildable native SDK.
The original DEX, native libraries and license configuration remain unchanged.

## Reproduce the local input

Run `python scripts/import-senplayer-runtime.py path/to/SenPlayer.apk` with your local input path.
The importer rejects a different APK and records each APK entry, byte count and SHA-256 in
`src/main/assets/senplayer/runtime.json`. DEX/native assets are locally generated and ignored by Git.
No runtime is downloaded. This import does not establish redistribution rights. Git contains the
bridge, importer and provenance metadata; the original DEX/native payload remains local. A fresh
checkout needs the same user-supplied APK and this import before the experimental engine is available.

## Runtime and licensing

`SenPlayerSession` loads the unchanged DEX with `DexClassLoader`, verifies extracted file hashes,
and keeps its three native libraries in a private loader directory. It does not replace the host's
`libc++_shared.so`. Extracted code is read-only before loading. Only Android 9+ (API 28+) ARM64 processes offer the
experimental engine; the existing 32-bit TV runtimes remain available.

The settings option explains the original license endpoint, `https://www.senmuse.com/api/license`.
Settings enumeration, app startup and automatic fallback never initialize this runtime. Explicitly
selecting SenPlayer and opening playback creates the original `eb2` verifier with the actual Yfuse
package/version, enforcement enabled and the original configuration. The protocol sends the current
app version, original runtime build/configuration, request ID and ephemeral public key. License
storage has its own no-backup subdirectory. The native runtime permit is only the remaining duration
of the original verifier's valid grant. Denial/expiry stops playback and reports an authorization
failure; no permit, signature, server identity or check is fabricated or patched.

The user initially declined an online test, then authorized one original license request on
2026-11-05. Device testing found that the unchanged original verifier can initialize a local
`allowed=true` window of approximately seven days with `refresh=false`, before sending any request.
This local grant is not proof that the server accepts Yfuse. The separately authorized single POST
returned HTTP 200 and was accepted by the original verifier. On the API 28 ARM64 test device,
playback advanced 5600 ms and GPU readback captured 184 frames with 64 distinct pixel checksums.
This was an isolated instrumentation test using the production bridge and original payload, not a
full Compose UI or audio test. The local evidence is in `audit/senplayer-license-test-20261105/`;
ABI checks alone do not prove playback, and this result is not a guarantee of future server grants.
An unavailable or expired original permit remains an authorization failure, without automatic
engine substitution. The bridge does not alter the original grant or its duration.

The original DEX delegates only to Android's framework class loader. A host-app parent is unsafe:
the two independently obfuscated APKs reuse short names such as `eb2`, `u54` and `oi`, which caused
an actual release-process `NoSuchMethodError`. The isolated loader passed device preflight.

## Adapter scope

`SenPlayerVideoEngine` supplies separate video and transparent subtitle surfaces, matching the
original `vp3` host, and serializes surface changes, source operations and polling on one worker. It
adapts queue selection, resume, play/pause, seek, speed, track selection, audio/subtitle delay, errors,
and release completion to `VideoEngine`. HTTP and HTTPS use the existing guarded playback proxy;
origin-scoped headers and cache configuration remain in that proxy. FTP/SMB/stable cloud transports
continue to use YCore. Cached ranges come from the original native progress ranges, with a separate
session/generation identity. Decoder/output strings are reported as observed, without inferring
HDR, Dolby Vision, audio passthrough or successful presentation from codec names.

The supplied runtime is pinned because its obfuscated ABI has no supported SDK contract. Every
reflection field/method is covered by the offline probe at
`audit/senplayer-integration-20261105/probe/SenAbiProbe.java`. An APK update requires a new ABI,
protocol and provenance review. DRM, optical discs, PiP, background controls, second subtitles and
external subtitles are not implemented by this adapter. The native binaries cannot be rebuilt or
audited internally from this APK alone. Static ELF/ZIP alignment checks do not establish
compatibility across modern devices or media formats.

## Evidence and remaining validation

The initial API 28 ARM64 device probe loaded the libraries, completed the decoder self-test and
resolved the reflection contract without initializing the license manager. The later, separately
authorized license/playback test used the locally delivered signed 1.8.7 / 339 package.

The 2026-11-05 playback review adds source/output lifecycle guards, unusable-session retirement,
original permit-state forwarding, sparse-cache validation and cancellation handling. The Java
bridge passes standalone compilation and 49 host assertions using doubles without loading JNI or
requesting a license. Kotlin policy and integration results are recorded in the local
`audit/playback-buffer-review-20261105/` reports. The new fixes still need full UI, slow-network,
Surface recreation and cross-device regression; the earlier device run cannot verify later changes.

### Missing-picture handling

The adapter accepts fresh native surface frames or draws for the current source and Surface.
When neither counter reports output, it samples a 16×16 buffer through `PixelCopy`, at most 20
times per source/seek/Surface generation and no more often than every 750 ms of polling while
playing. Two distinct non-flat samples are required; a successful copy, media-clock advancement
or decoded-frame count alone never declares the video rendered. Samples stay in memory and are
recycled; logs contain result codes and a luminance range, never images or pixel fingerprints.

For SenPlayer only, the artwork/entrance cover gives way after three seconds to expose the actual
Surface and a compact waiting message. This does not change rendering diagnostics. After eight
seconds of active playback progress with decoded frames but no output evidence, the adapter may
detach/rebind the current valid Surface once per source or explicit seek. Buffering, pauses and
missing Surfaces earn no recovery time. Continued missing output produces a decoder/engine hint;
it does not reopen the source, choose another engine or modify the original runtime.

`player.senplayer/video_output_sample` records native decoder/output labels, all three frame
counters, Surface generation/readiness and pixel-copy results every five seconds or on a phase
change. Recovery actions use `video_output_recovery`. Rebuffer counts and durations now include
starvation after playback starts even if first-picture confirmation is missing; startup and seeks
are excluded.

`SenPlayerOutputPolicyTest` covers output evidence, stale generations, one-shot recovery and
rebuffer accounting. Device regression must still cover the reported ProRes MOV, software and
embedded decoding, rotation/background return, seeking, pause and slow-network buffering. Host
tests and Android API compilation cannot establish that the reported movie displays on the OPPO
device or that the original native decoder supports that source.
