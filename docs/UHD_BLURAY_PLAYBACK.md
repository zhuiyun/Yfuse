# UHD Blu-ray playback contract

Yfuse treats Blu-ray as a playback graph, not as a file extension. The implementation preserves an
already-resolved server main feature when possible, and uses a capability-gated native libbluray path
for raw images. A feature is release-supported only after the exact native AAR and physical-device
corpus pass the gates below; source code alone is not evidence of device support.

## Current routes

| Source | Implemented route | Release status |
| --- | --- | --- |
| Emby/Jellyfin resolved `.m2ts/.mts/.ts` main feature | DirectStream -> YCore device planner | implemented; physical HDR/audio corpus still required |
| Local seekable Blu-ray ISO (`file://` or seekable `content://`) | `yfusebd://` -> libbluray `bd_open_stream` -> mpv | source implemented; custom AAR build + device validation blocked by CI billing |
| Filesystem BDMV directory | existing mpv/libbluray `bd://longest` route | main-feature/title route retained; unified Yfuse HDMV provider waits for the `bd_open_files` VFS bridge |
| Remote raw ISO from a saved server | authenticated HTTP Range -> 2048-byte block source -> `yfusebd://` -> `bd_open_stream` | source implemented; custom AAR build + real-origin/device validation still required |
| Remote raw ISO when native capability is absent/fails | server main-feature/transcode fallback | implemented safety fallback |

A server-resolved disc DirectStream is not treated as the raw disc image. The planner records
`discMainFeatureResolved` so an original MediaSource marked ISO/BDMV cannot force an otherwise valid
linear M2TS stream back through server ffmpeg.

## Reproducible native engine and binary gate

The stock `libmpv-android` v1.0.0 AAR does not contain libbluray. Yfuse therefore owns a pinned native
build lane instead of assuming that a Kotlin `bd://` URL proves binary support.

`build-yfuse-mpv-bluray.sh` pins:

- libmpv-android `fcf6745703dc1265bca88f12fee8fc355ddf251e`;
- libbluray `7d94f2660af5bfc16015291a03539329135c18f1` (1.4.1);
- libudfread `139a2194525f2745b98a98e4d8fa627d07440176`.

The build fails unless mpv generates `HAVE_LIBBLURAY=1`. It then compiles the private
`yfusebd://` stream and embeds `dev.yfuse.mpv.YfuseMpvCapabilities` plus
`dev.yfuse.mpv.YfuseBluRayRegistry`. The marker currently states:

- `LIBBLURAY=true`;
- `REMOTE_RAW_BLURAY=true`;
- `HDMV_MENU=true`;
- `BDJ=false`.

`verify-yfuse-mpv-bluray-aar.sh` validates the exact revisions, SHA-256, marker/registry classes,
JNI symbols, private protocol, ARM64 ELF architecture and every PT_LOAD alignment against Android's
16 KiB page requirement before installation/promotion. `install-yfuse-mpv-bluray.sh` reuses that
verifier and replaces the app AAR only after all checks pass.

The build workflow is wired to branch/PR native changes. At the time this document was updated the
GitHub runner is not starting because the repository account reports failed payments/spending limit;
therefore the new native bridge has **not yet produced a verified AAR**. Do not promote the capability
marker to a release claim until the runner actually executes the build.

## Remote ISO transport

`HttpRangeDiscBlockSource` and `NativeRemoteBluRayBlockSource` implement the transport side expected
by libbluray/libudfread:

- 2048-byte UDF logical blocks;
- 64-bit byte offsets and 100 GiB+ safe arithmetic;
- exact HTTP 206 and `Content-Range` validation;
- `Accept-Encoding: identity` and `Cache-Control: no-transform`;
- hard rejection of an origin that ignores Range and returns 200;
- no automatic cross-origin redirect while credentials are present;
- authentication headers resolved for every request so a renewed token can be used without rebuilding
  the playback item;
- 416 with `bytes */N` treated as EOF;
- 64 KiB read-ahead, a 512 KiB hard ceiling per callback and a bounded ~4 MiB LRU media-byte cache;
- source URL/token excluded from diagnostics.

Only the active optical-disc queue item is registered with JNI, preventing unused queued images from
retaining Java global references. The original server transcode/progressive URLs remain on the item so
a native failure can still enter the existing recovery chain.

## `yfusebd` libbluray/mpv stream

`scripts/native/stream_yfuse_bluray.c` is copied into the pinned mpv checkout during the native build.
It implements:

- process-local source registration with JNI global-reference lifetime management;
- `bd_init` + `bd_open_stream(read_blocks)` for raw ISO block sources;
- main-title selection, title/chapter/time/angle stream controls and disc-name reporting;
- rejection of non-Blu-ray input and unhandled AACS/BD+ content;
- a mutex around all libbluray calls shared by mpv read/control and Android menu input;
- lazy navigation-mode transition when an authored menu is requested;
- `bd_read_ext` event processing while in navigation mode;
- HDMV D-pad/select/menu input through `bd_user_input` and touch through `bd_mouse_select`;
- `BD_EVENT_MENU`, `POPUP`, `PLAYLIST`, `TITLE`, `CHAPTER`, `ANGLE`, `SEEK`, `DISCONTINUITY`,
  `STILL_TIME`, `ERROR` and `ENCRYPTED` state handling;
- Interactive Graphics overlay decoding only; ordinary movie PGS remains the mpv subtitle path;
- bounded RLE -> ARGB composition and overlay pushes on FLUSH, with clear/hide/close propagation.

The Android side receives native navigation and overlay pushes, combines the HDMV provider with the
current mpv title/chapter backend, renders the authored overlay over the video Surface, maps touch
coordinates through ContentScale.Fit and consumes D-pad/back only while a real menu runtime reports an
active menu. Native menu failure remains isolated from the video engine.

This is implemented source, **not yet physical-disc proof**. In particular, transition from direct
main-title mode into `bd_play()` navigation mode, authored still frames, menu sound effects, unusual
multi-angle/menu combinations and overlay color matching require a real-disc/device corpus before a
release support claim.

## Local ISO and BDMV

Known local Blu-ray ISO files can use the same `yfusebd` session. `file://` images are opened through a
read-only ParcelFileDescriptor and `content://` images use the provider's seekable file descriptor;
`Os.pread` avoids copying giant images and keeps offsets 64-bit.

Generic `.iso` files are not routed until the bounded image classifier or trusted metadata establishes
Blu-ray, preventing DVD images from being misrouted to libbluray.

Filesystem BDMV directories still use the existing `bd://longest` path. A single descriptor cannot
represent a directory tree, so Android SAF/tree BDMV and unified-menu BDMV require a real
`bd_open_files(open_dir, open_file)` VFS adapter rather than a fake path conversion.

## Title, MPLS, chapter and menu UI

The existing mpv route reads `edition-list` and `chapter-list` and preserves authored title id/name,
default flag, explicit MPLS hints, chapter name and timestamp. Count-only backends remain supported.
The player settings panel directly selects an arbitrary Title/Playlist or Chapter rather than forcing
repeated next commands.

`DiscNavigationBackend` separates optical navigation from video decode. A
`CompositeDiscNavigationBackend` keeps title/chapter control on mpv while the optional HDMV provider
owns only menu input/overlay. `ActiveDiscNavigation` is owner-scoped so an outgoing engine cannot clear
or receive commands intended for a replacement engine.

## HDR, Dolby Vision, audio and subtitles

HDR10, HDR10+, HLG and Dolby Vision continue through the existing decoder/display capability planner.
Dolby-only streams are not intentionally handed to ordinary HEVC fallback merely to avoid a server
route. Server Dolby metadata keeps profile plus RPU/EL/BL flags and compatibility id.

A P7 source with EL present is described only as dual-layer source evidence. Yfuse still does **not**
claim Full Enhancement Layer composition. MEL/FEL distinction and actual enhancement-layer composition
require a decoder/render pipeline and physical evidence; `ElPresentFlag` alone is not FEL proof.

TrueHD/Atmos, E-AC-3 JOC, DTS/DTS-HD and PCM continue through route-aware Android output negotiation.
Passthrough is reported active only when the active route proves encoded output. PGS can select the mpv
native renderer without turning a valid resolved Blu-ray DirectStream into server transcode.

## Explicit non-claims

- **BD-J is not supported.** The custom native build explicitly uses `bdj_jar=disabled`; a separately
  maintainable Android Java/Xlet runtime is required before this can change.
- **Dolby Vision P7 FEL composition is not claimed.** Source metadata recognition is not reconstruction.
- **Encrypted commercial-disc circumvention is not included.** AACS/BD+ handling depends on external,
  legally supplied components/keys; Yfuse does not ship bypass material.
- **Native ISO/HDMV is not release-validated yet.** The current GitHub Actions runner is blocked before
  job start, so compilation/native linking and physical-device validation remain gates.

## Release gates

Before merging/releasing this branch, all of the following must have evidence:

1. PR compile/unit/lint/R8/package gates actually execute and pass.
2. The custom AAR build executes, proves `HAVE_LIBBLURAY=1`, passes the verifier and records the exact
   SHA-256/source manifest.
3. Local ISO: main feature, direct title/chapter selection, random seek, resume, EOF and 100 GiB+
   offset coverage.
4. Remote ISO: 206/identity/no-redirect/token-refresh/416/large-offset tests plus real authenticated
   server playback and server fallback on native failure.
5. HDMV: root/popup menu, D-pad/select/back, touch, overlay clear/flush, still frame and failure
   isolation on authored discs.
6. HDR10/HDR10+/HLG/Dolby Vision samples on hardware that advertises those outputs; P7 FEL remains
   NotMeasured unless enhancement-layer composition is independently proven.
7. TrueHD/Atmos and DTS-HD over HDMI/eARC plus safe PCM fallback on speaker/Bluetooth.
8. PGS selection/rendering while preserving DirectStream.
9. 16 KiB page-size install/load on Android 15/16 and a full ordinary-media regression including the
   separate 100 GiB+ ProRes/MOV path.
10. Staged crash-free, recovery, A/V-sync, dropped-frame, rebuffer, power, thermal and soak gates from
    `YCORE_VALIDATION_MATRIX.md`.

The core regression remains: **a valid server-resolved linear Blu-ray main feature must never become
`ServerTranscode` solely because the original MediaSource was a disc image.**
