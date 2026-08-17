# UHD Blu-ray playback contract

Yfuse treats a Blu-ray disc source as a playback graph rather than a file extension. The goal is to
preserve the original picture, audio and subtitle streams whenever the server or local native engine
can expose a linear main feature, while keeping unsafe raw-image and unsupported licensed paths
explicit.

## Supported routes

| Source | Route | Expected preservation |
| --- | --- | --- |
| Emby/Jellyfin resolved `.m2ts` / `.mts` / `.ts` main feature | `DirectStream` -> YCore device planning | HEVC/HDR/Dolby metadata carried by the stream, lossless audio and PGS stay available when the backend/output route supports them |
| Local Blu-ray ISO / BDMV | libmpv + libbluray native route | title/chapter selection, video/audio/subtitle tracks; no server transcode |
| Remote raw ISO / BDMV without a linear direct stream | server main-feature fallback | server chooses the feature; transcoding is allowed only because no directly playable title was exposed |

A negotiated disc `DirectStream` is not the raw disc image. YCore marks it as
`discMainFeatureResolved`, does not force the native image demuxer and does not force server ffmpeg
merely because the original MediaSource is an ISO/BDMV source.

## HDR and Dolby

- HDR10, HDR10+, HLG and Dolby Vision continue through the existing device/display capability
  planner.
- A Dolby-only stream uses the verified Android Dolby Vision platform path when the device declares
  compatible decode and display support. It is never intentionally decoded as ordinary HEVC merely
  to avoid a fallback.
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

The current native contract exposes Blu-ray title/edition and chapter selection. It does not claim
HDMV interactive menus or BD-J support. `PlaybackDiscMenuCommand` therefore remains unsupported for
mpv until a backend is integrated that can prove menu navigation and Java runtime behavior.

This boundary is intentional: a fake menu control is worse than an explicit unsupported capability.
Encrypted commercial-disc access also depends on external/licensed components and keys; Yfuse does
not ship or emulate circumvention material.

## Release validation

A UHD Blu-ray release lane must include, where legally available:

- 4K HEVC Main10 HDR10 main feature via server-resolved `.m2ts` DirectStream.
- HDR10+, HLG and Dolby Vision samples on devices that advertise the corresponding output.
- TrueHD/Atmos and DTS-HD over HDMI/eARC, plus PCM fallback on speaker/Bluetooth routes.
- PGS subtitle selection and rendering while preserving the same direct-stream URL.
- Local ISO/BDMV title and chapter changes, seeks and resume.
- A raw remote ISO case proving it still falls back to server main-feature parsing instead of being
  mistaken for a linear stream.

The critical regression assertion is: **a valid server-resolved linear Blu-ray main feature must not
be converted into `ServerTranscode` solely because its original MediaSource was a disc image.**
