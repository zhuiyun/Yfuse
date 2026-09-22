# Third-party software notices

This file records the principal native components shipped by the Android APK.
It is not a substitute for a release-time legal review or the generated SBOM.

| Component | Pinned artifact | Upstream license/source reference | Notes |
| --- | --- | --- | --- |
| libmpv Android | `libmpv-android` v1.0.0 | <https://github.com/jarnedemeulemeester/libmpv-android> | Bundles mpv and FFmpeg-family native libraries. Preserve upstream notices and comply with the licenses of the exact build configuration. |
| libbluray | `7d94f2660af5bfc16015291a03539329135c18f1` (1.4.1) | <https://code.videolan.org/videolan/libbluray/-/tree/7d94f2660af5bfc16015291a03539329135c18f1> | LGPL-2.1-or-later; see [LGPL-2.1.txt](LGPL-2.1.txt). The Yfuse build disables BD-J. |
| libudfread | `139a2194525f2745b98a98e4d8fa627d07440176` | <https://code.videolan.org/videolan/libudfread/-/tree/139a2194525f2745b98a98e4d8fa627d07440176> | LGPL-2.1-or-later; see [LGPL-2.1.txt](LGPL-2.1.txt). Pulled as libbluray's pinned submodule. |
| mpv | Included through libmpv Android | <https://github.com/mpv-player/mpv/blob/master/Copyright> | mpv's effective license depends on build options; verify the downloaded binary's configuration before distribution. |
| FFmpeg | Included by native player engines | <https://ffmpeg.org/legal.html> | LGPL/GPL obligations depend on enabled components and link/build options. |
| MDK SDK | `mdk-sdk` v0.37.0 | <https://github.com/wang-bin/mdk-sdk> | The artifact README states that free use is limited to specified categories. Commercial or other distribution requires confirming eligibility or obtaining an appropriate license. |
| dav1d | Included by MDK | <https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING> | BSD 2-Clause. |
| libass | Statically linked into the standalone YCore native runtime | <https://github.com/libass/libass/blob/master/COPYING> | ISC license. |
| FreeType | Statically linked into the standalone YCore native runtime through libass | <https://gitlab.freedesktop.org/freetype/freetype/-/blob/master/docs/FTL.TXT> | FreeType License or GPLv2; YCore uses the FreeType License option. |
| FriBidi | Statically linked into the standalone YCore native runtime through libass | <https://github.com/fribidi/fribidi/blob/master/COPYING> | LGPL-2.1-or-later. |
| HarfBuzz | Statically linked into the standalone YCore native runtime through libass | <https://github.com/harfbuzz/harfbuzz/blob/main/COPYING> | Old MIT license. |
| libunibreak | Statically linked into the standalone YCore native runtime through libass | <https://github.com/adah1972/libunibreak/blob/master/LICENCE> | zlib/libpng license. |
| libdvdnav / libdvdread | Not bundled in the ordinary YCore package | <https://code.videolan.org/videolan/libdvdnav> / <https://code.videolan.org/videolan/libdvdread> | FFmpeg's `dvdvideo` demuxer requires these GPL libraries and a GPL-enabled FFmpeg build. DVD remains fail-closed unless a separately reviewed compatible distribution profile is created. |
| jcifs-ng | `eu.agno3.jcifs:jcifs-ng:2.1.10` | <https://github.com/AgNO3/jcifs-ng/tree/jcifs-ng-2.1.10> | LGPL-2.1; used for the SMB2/SMB3 random-access transport. Preserve notices and relinking/source obligations. |
| Cronet | `com.google.android.gms:play-services-cronet:18.0.1` | <https://developer.android.com/develop/connectivity/cronet> | Chromium networking API distributed through Google Play services; enables negotiated HTTP/2 and HTTP/3/QUIC. |
| Manrope font | App font asset | [Manrope-OFL.txt](Manrope-OFL.txt) | SIL Open Font License 1.1. |

Before every production release:

1. Review the exact native artifacts named in `scripts/engine-checksums.sha256`.
2. Archive the upstream license/notices that correspond to those exact versions.
3. Confirm MDK usage rights for the intended distribution and commercial status.
4. Retain the generated SPDX SBOM with the release artifact.
5. Make required notices available to recipients; do not rely on this summary alone.
