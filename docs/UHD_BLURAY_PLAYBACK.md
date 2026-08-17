# UHD Blu-ray playback contract

Yfuse treats a Blu-ray disc source as a playback graph rather than a file extension. The goal is to
preserve the original picture, audio and subtitle streams whenever the server or local native engine
can expose a linear main feature, while keeping unsafe raw-image and unsupported licensed paths
explicit.

## Supported routes

| Source | Route | Expected preservation |
| --- | --- | --- |
| Emby/Jellyfin resolved `.m2ts` / `.mts` / `.ts` main feature | `DirectStream` -> YCore device planning | HEVC/HDR/Dolby metadata carried by the stream, lossless audio and PGS stay available when the backend/output route supports them |
| Local Blu-ray ISO / BDMV | libmpv + libbluray `bd://longest` | longest playlist starts first, rich title/chapter metadata is exposed when available, video/audio/subtitle tracks stay local; no server transcode |
| Remote raw ISO / BDMV without a linear direct stream | server main-feature fallback | server chooses the feature; transcoding is allowed only because no directly playable title was exposed |

A negotiated disc `DirectStream` is not the raw disc image. YCore marks it as
`discMainFeatureResolved`, does not force the native image demuxer and does not force server ffmpeg
merely because the original MediaSource is an ISO/BDMV source.

For local Blu-ray sources Yfuse explicitly opens `bd://longest` instead of relying on libbluray's
implicit first/default playlist. Users can still switch the exposed Blu-ray title/edition afterwards.
This reduces the common failure mode where a short bonus playlist or studio logo opens instead of the
main feature.

Remote raw ISO/BDMV is intentionally not advertised as native direct playback. The current libmpv
integration gives libbluray a filesystem/ISO path, not a credentialed HTTP random-access block device.
Until Yfuse has a bounded authenticated range-reader that can satisfy libbluray's seeks, pretending a
remote ISO URL is a linear movie is unsafe; the server main-feature route remains the correct fallback.

## Title, MPLS and chapter navigation

The native mpv route now reads the rich `edition-list` and `chapter-list` property trees rather than
keeping only their counts. When the backend exposes them, YCore retains:

- title/edition index, backend id, authored name and default flag;
- an explicit MPLS number when the title text contains a real `00001.mpls` / `MPLS/00001` style hint;
- chapter authored name and start timestamp;
- count-only fallback rows when a disc/backend exposes only `editions` / `chapters`.

Playback Settings shows the complete title/playlist and chapter lists for an active native disc and
selects the requested row directly. It no longer requires repeatedly pressing “next title” or “next
chapter” to reach a known target. An MPLS number is never guessed from an arbitrary number in a title;
only an explicit MPLS-shaped hint is promoted to playlist metadata.

Optical navigation is now isolated behind `DiscNavigationBackend`. The current mpv engine is adapted
into that contract, while an owner-scoped process-local binding lets the common UI issue a navigation
command without owning the decoder. Engine handover cannot let an outgoing engine clear a newer
binding. This is the seam for a future HDMV/BD-J provider; failure or absence of that optional provider
must not break ordinary main-feature playback.

## HDR and Dolby

- HDR10, HDR10+, HLG and Dolby Vision continue through the existing device/display capability
  planner.
- A Dolby-only stream uses the verified Android Dolby Vision platform path when the device declares
  compatible decode and display support. It is never intentionally decoded as ordinary HEVC merely
  to avoid a fallback.
- Yfuse preserves the server's Dolby Vision `RpuPresentFlag`, `ElPresentFlag` and `BlPresentFlag`.
  A Profile 7 source with an enhancement layer is labeled as a **dual-layer** source; this is source
  evidence only, not proof that the device composed a Full Enhancement Layer.
- If the server says no base layer is present, YCore treats the stream as Dolby-decoder-required even
  if another compatibility field is inconsistent. This prevents an EL/RPU stream from being handed
  to an ordinary HEVC path that cannot render it correctly.
- Dolby Vision Profile 7 FEL reconstruction is **not claimed**. Playback may use the device/base-layer
  path available for the selected stream, but a release must not label FEL as active without physical
  device evidence that the enhancement layer is being composed.
- HDR-to-SDR conversion remains the mpv GPU tone-mapping path on devices that cannot present the
  source HDR range.

## Audio

TrueHD/Atmos, E-AC-3 JOC, DTS and DTS-HD keep using the existing route-aware passthrough policy.
Encoded output is reported active only when the Android audio route/backend proves that an encoded
bitstream is leaving the device. Otherwise playback safely falls back to decoded PCM.

## Subtitles

PGS and other styled bitmap subtitles are treated as native-renderer content. If a server-resolved
Blu-ray main feature exposes PGS, YCore may move the session from Exo to mpv without changing the
media URL or starting a server transcode.

## Navigation boundary

The current native route exposes title/edition and chapter selection, including names/timestamps when
mpv provides them. It does not claim HDMV interactive menus or BD-J support. `PlaybackDiscMenuCommand`
therefore remains unsupported for mpv until a backend is integrated that can prove menu navigation and
Java runtime behavior.

This boundary is intentional: a fake menu control is worse than an explicit unsupported capability.
Encrypted commercial-disc access also depends on external/licensed components and keys; Yfuse does
not ship or emulate circumvention material.

The isolation layer for the next menu milestone now exists: `DiscNavigationBackend` is independent of
video decoding. The remaining work is a real HDMV/BD-J-capable provider, capability detection and
lifecycle/failure integration — not simulated menu commands in mpv.

## Release validation

A UHD Blu-ray release lane must include, where legally available:

- 4K HEVC Main10 HDR10 main feature via server-resolved `.m2ts` DirectStream.
- HDR10+, HLG and Dolby Vision samples on devices that advertise the corresponding output.
- Dolby Vision P7 samples with RPU/EL/BL metadata, proving that source-layer evidence is preserved
  without turning an `ElPresentFlag` into a false FEL-output claim.
- TrueHD/Atmos and DTS-HD over HDMI/eARC, plus PCM fallback on speaker/Bluetooth routes.
- PGS subtitle selection and rendering while preserving the same direct-stream URL.
- Local ISO/BDMV startup on the longest playlist, direct selection of non-adjacent titles and
  chapters, authored names/timestamps, seeks and resume.
- A count-only optical-disc sample proving rich metadata is optional and navigation still works.
- An explicit MPLS-title sample proving playlist numbers are preserved without guessing arbitrary
  numbers from title text.
- A raw remote ISO case proving it still falls back to server main-feature parsing instead of being
  mistaken for a linear stream.

The critical regression assertion is: **a valid server-resolved linear Blu-ray main feature must not
be converted into `ServerTranscode` solely because its original MediaSource was a disc image.**
