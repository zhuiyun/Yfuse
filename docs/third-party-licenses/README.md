# Third-party software notices

This file records the principal native components shipped by the Android APK.
It is not a substitute for a release-time legal review or the generated SBOM.

| Component | Pinned artifact | Upstream license/source reference | Notes |
| --- | --- | --- | --- |
| libmpv Android | `libmpv-android` v1.0.0 | <https://github.com/jarnedemeulemeester/libmpv-android> | Bundles mpv and FFmpeg-family native libraries. Preserve upstream notices and comply with the licenses of the exact build configuration. |
| mpv | Included through libmpv Android | <https://github.com/mpv-player/mpv/blob/master/Copyright> | mpv's effective license depends on build options; verify the downloaded binary's configuration before distribution. |
| FFmpeg | Included by native player engines | <https://ffmpeg.org/legal.html> | LGPL/GPL obligations depend on enabled components and link/build options. |
| MDK SDK | `mdk-sdk` v0.37.0 | <https://github.com/wang-bin/mdk-sdk> | The artifact README states that free use is limited to specified categories. Commercial or other distribution requires confirming eligibility or obtaining an appropriate license. |
| dav1d | Included by MDK | <https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING> | BSD 2-Clause. |
| libass | Included by MDK | <https://github.com/libass/libass/blob/master/COPYING> | ISC license. |
| Manrope font | App font asset | [Manrope-OFL.txt](Manrope-OFL.txt) | SIL Open Font License 1.1. |

Before every production release:

1. Review the exact native artifacts named in `scripts/engine-checksums.sha256`.
2. Archive the upstream license/notices that correspond to those exact versions.
3. Confirm MDK usage rights for the intended distribution and commercial status.
4. Retain the generated SPDX SBOM with the release artifact.
5. Make required notices available to recipients; do not rely on this summary alone.
