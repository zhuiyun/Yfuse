# UHD Blu-ray playback contract

Yfuse treats a Blu-ray disc source as a playback graph rather than a file extension. The goal is to
preserve the original picture, audio and subtitle streams whenever the server or local native engine
can expose a linear main feature, while keeping unsafe raw-image and unsupported licensed paths
explicit.

## Supported routes

| Source | Route | Expected preservation |
| --- | --- | --- |
| Emby/Jellyfin resolved `.m2ts` / `.mts` / `.ts` main feature | `DirectStream` -> YCore device planning | HEVC/HDR/Dolby metadata carried by the stream, lossless audio and PGS stay available when the backend/output route supports them |
| Local Blu-ray ISO / BDMV with the Yfuse libbluray-enabled AAR | libmpv + libbluray `bd://longest` | longest playlist starts first, rich title/chapter metadata is exposed when available, video/audio/subtitle tracks stay local; no server transcode |
| Remote raw ISO / BDMV today | server main-feature fallback | range-reader foundation exists, but raw direct playback stays disabled until the libbluray `bd_open_stream` JNI/session bridge is built and device-validated |

A negotiated disc `DirectStream` is not the raw disc image. YCore marks it as
`discMainFeatureResolved`, does not force the native image demuxer and does not force server ffmpeg
merely because the original MediaSource is an ISO/BDMV source.

For local Blu-ray sources Yfuse explicitly opens `bd://longest` instead of relying on libbluray's
implicit first/default playlist. Users can still switch the exposed Blu-ray title/edition afterwards.
This reduces the common failure mode where a short bonus playlist or studio logo opens instead of the
main feature.

## Native engine binary gate

The stock `libmpv-release.aar` fetched by `scripts/fetch-engines.sh` comes from the pinned
`libmpv-android` v1.0.0 release. That upstream build's dependency tree contains FFmpeg, libass, Lua,
libplacebo and related libraries, but **does not contain libbluray**. Therefore source code that knows
how to form `bd://longest` is not by itself evidence that the shipped binary can open a Blu-ray image.

Yfuse now has a separate reproducible build/install lane:

- `scripts/build-yfuse-mpv-bluray.sh` pins libmpv-android, VideoLAN libbluray 1.4.1 and libudfread to
  exact revisions, links libbluray into mpv, and fails unless generated mpv config contains
  `HAVE_LIBBLURAY=1`;
- the custom AAR embeds `dev.yfuse.mpv.YfuseMpvCapabilities` **only after** the `HAVE_LIBBLURAY` gate
  passes. Runtime detects this class by reflection, so a stock AAR cannot accidentally claim native
  Blu-ray support;
- `scripts/install-yfuse-mpv-bluray.sh` checks SHA-256, exact native source revisions, the embedded
  capability marker, AAR layout and ARM64 ELF before replacing `composeApp/libs/libmpv-release.aar`;
- `scripts/fetch-engines.sh` removes custom provenance sidecars when it restores the stock AAR, so an
  old build cannot leave a stale capability claim behind;
- `.github/workflows/build-yfuse-mpv-bluray.yml` is the manual reproducible build lane and uploads the
  AAR, checksum and exact source manifest;
- libbluray is built with `bdj_jar=disabled`. This proves native libbluray/title/HDMV foundations only,
  **not** BD-J.

`PlayerEngineFactory` also checks the capability marker before constructing mpv for a *known local*
Blu-ray/BDMV source. If the installed AAR is stock, Yfuse fails immediately with a native-capability
message instead of pretending `bd://longest` can work. Generic ISO stays unblocked until the bounded
image inspector knows whether it is Blu-ray or DVD.

A release must not claim native local ISO/BDMV support until the produced Yfuse AAR has replaced the
stock AAR, its checksum is pinned, and a physical-device ISO/BDMV corpus passes the validation matrix.

## Remote raw ISO random access

The missing transport half for remote raw ISO is now implemented as
`HttpRangeDiscBlockSource`. It is intentionally shaped around libbluray/libudfread's 2048-byte UDF
block callback instead of pretending an ISO is a linear HTTP movie.

The reader:

- converts logical block addresses to 64-bit byte offsets and is tested above 2 GiB/4 GiB boundaries;
- requires exact HTTP `206 Partial Content` and validates `Content-Range`;
- forces `Accept-Encoding: identity` and `Cache-Control: no-transform` so byte offsets remain stable;
- rejects ordinary `200 OK` responses instead of accidentally downloading a complete 50–100+ GiB ISO;
- refuses redirects so account/API authorization headers are never forwarded to another origin;
- resolves authorization headers for every request, allowing an expiring token to be refreshed without
  rebuilding the native disc session;
- understands `416` as EOF when the server supplies the total length;
- refuses main-thread reads and becomes inert after close;
- never places the source URL/token in diagnostics.

This is a **foundation, not a live playback route yet**. The remaining native work is to connect this
reader to libbluray's `bd_open_stream` callback through a JNI session, then expose libbluray events,
overlays, title/chapter/menu state through `HdmvDiscSession`. Until that bridge is compiled into the
custom AAR and validated against real authenticated ISO origins, YCore keeps remote raw ISO on the
server main-feature fallback.

## Title, MPLS and chapter navigation

The native mpv route reads the rich `edition-list` and `chapter-list` property trees rather than
keeping only their counts. When the backend exposes them, YCore retains:

- title/edition index, backend id, authored name and default flag;
- an explicit MPLS number when the title text contains a real `00001.mpls` / `MPLS/00001` style hint;
- chapter authored name and start timestamp;
- count-only fallback rows when a disc/backend exposes only `editions` / `chapters`.

Playback Settings shows the complete title/playlist and chapter lists for an active native disc and
selects the requested row directly. It no longer requires repeatedly pressing “next title” or “next
chapter” to reach a known target. An MPLS number is never guessed from an arbitrary number in a title;
only an explicit MPLS-shaped hint is promoted to playlist metadata.

Optical navigation is isolated behind `DiscNavigationBackend`. The current mpv engine is adapted into
that contract, while an owner-scoped process-local binding lets common UI issue a navigation command
without owning the decoder. Engine handover cannot let an outgoing engine clear a newer binding.

The contract reports backend lifecycle plus actual interactive menu runtime (`None`, `Hdmv`, or
`BdJ`) and supports asynchronous native state pushes. `HdmvDiscNavigationBackend` wraps a future JNI
session with a hard failure boundary: a native menu failure marks only that optional provider failed
and clears menu-active state; it cannot escape into video playback.

Android D-pad/enter/menu/back routing is installed only while a provider reports both a ready
interactive runtime and an active menu. Ordinary playback therefore keeps normal Activity and back
behavior. Provider closure/failure removes the interception instead of trapping the viewer.

## HDR and Dolby

- HDR10, HDR10+, HLG and Dolby Vision continue through the existing device/display capability planner.
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

The current engine-backed route exposes title/edition and chapter selection, including names and
timestamps when mpv provides them. `HdmvDiscSession` / `HdmvDiscNavigationBackend` now define the
failure-isolated provider seam and Android input behavior, but a real libbluray JNI overlay/event
session has not yet been built into the AAR.

BD-J remains a separate milestone. The current native build deliberately disables the BD-J JAR, and
Yfuse does not claim a Java runtime simply because libbluray is present.

Encrypted commercial-disc access also depends on external/licensed components and keys; Yfuse does
not ship or emulate circumvention material.

## Release validation

A UHD Blu-ray release lane must include, where legally available:

- 4K HEVC Main10 HDR10 main feature via server-resolved `.m2ts` DirectStream.
- HDR10+, HLG and Dolby Vision samples on devices that advertise the corresponding output.
- Dolby Vision P7 samples with RPU/EL/BL metadata, proving that source-layer evidence is preserved
  without turning an `ElPresentFlag` into a false FEL-output claim.
- TrueHD/Atmos and DTS-HD over HDMI/eARC, plus PCM fallback on speaker/Bluetooth routes.
- PGS subtitle selection and rendering while preserving the same direct-stream URL.
- The exact release AAR must prove `HAVE_LIBBLURAY=1`, contain the Yfuse capability marker and match
  its pinned SHA-256/source manifest before native ISO/BDMV tests are accepted.
- Local ISO/BDMV startup on the longest playlist, direct selection of non-adjacent titles and chapters,
  authored names/timestamps, seeks and resume.
- A count-only optical-disc sample proving rich metadata is optional and navigation still works.
- An explicit MPLS-title sample proving playlist numbers are preserved without guessing arbitrary
  numbers from title text.
- Remote ISO transport tests must prove exact 206 ranges, identity encoding, no credential redirect,
  64-bit offsets, 416 EOF behavior and token refresh; this still does not enable the route until the
  `bd_open_stream` JNI bridge passes physical-device validation.
- Interactive-menu input tests must show D-pad/select/back are consumed only while a verified menu is
  active; closing/failing the menu must restore normal Android back/key behavior.

The critical regression assertion remains: **a valid server-resolved linear Blu-ray main feature must
not be converted into `ServerTranscode` solely because its original MediaSource was a disc image.**
