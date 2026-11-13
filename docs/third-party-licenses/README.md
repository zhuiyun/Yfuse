# Third-party software notices

This file records the principal native components shipped by the Android APK.
It is not a substitute for a release-time legal review or the generated SBOM.

| Component | Pinned artifact | Upstream license/source reference | Notes |
| --- | --- | --- | --- |
| QuickJS-KT | `io.github.dokar3:quickjs-kt:1.0.15` | [Upstream](https://github.com/dokar3/quickjs-kt), Apache-2.0 | JS component runtime; license ships in `assets/widget-licenses/quickjs-kt-Apache-2.0.txt`. Its native dependencies QuickJS and c-vector carry the accompanying MIT notices. |
| jsoup | `org.jsoup:jsoup:1.23.2` | [Upstream](https://github.com/jhy/jsoup/tree/jsoup-1.23.2), MIT | Component HTML parsing; license ships in `assets/widget-licenses/jsoup-MIT.txt`. |
| Anime4K Original | Vendored `Anime4K_Upscale_Original_x2.glsl` | [Upstream](https://github.com/bloc97/Anime4K), MIT | Shader notices and full LICENSE ship in `assets/anime4k/`; native GLES port preserves the six Original passes. |
| libbluray | `7d94f2660af5bfc16015291a03539329135c18f1` (1.4.1) | <https://code.videolan.org/videolan/libbluray/-/tree/7d94f2660af5bfc16015291a03539329135c18f1> | LGPL-2.1-or-later; see [LGPL-2.1.txt](LGPL-2.1.txt). The Yfuse build disables BD-J. |
| libudfread | `139a2194525f2745b98a98e4d8fa627d07440176` | <https://code.videolan.org/videolan/libudfread/-/tree/139a2194525f2745b98a98e4d8fa627d07440176> | LGPL-2.1-or-later; see [LGPL-2.1.txt](LGPL-2.1.txt). Pulled as libbluray's pinned submodule. |
| FFmpeg | Linked by YCore. Commit recorded in scripts/ycore-dependencies.json. | <https://ffmpeg.org/legal.html> | The native build records the effective license reported by libavutil in NATIVE-SOURCES.txt. |
| dav1d | Linked by YCore through FFmpeg | <https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING> | BSD 2-Clause. |
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

1. Review the YCore source lock, the native build receipt and the pinned SenPlayer assets.
2. Archive the upstream license/notices that correspond to those exact versions.
3. Retain the generated SPDX SBOM with the release artifact.
4. Make required notices available to recipients; do not rely on this summary alone.
