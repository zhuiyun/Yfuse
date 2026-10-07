# YCore fixes — package request 1.1.4 (266)

The owner requested a signed package after the ten YCore audit fixes were merged in #222.
Base: `aab5159cf9873ccce2997339113b1e5749cfd8dd` on `master`.

## Version and scope

- Last delivered APK: 1.1.3 (265), verified in package-only run [37308622072](https://github.com/zhuiyun/Yfuse/actions/runs/37308622072).
- Previous APK SHA-256: `b81125e1d0659094717e86a7d5a334095bca87df416e9e7dd9300a52b47d8cb3`, 29,422,566 bytes.
- New delivery: 1.1.4 (266). Both `version.properties` and `release-notes.txt` have been updated, retaining historical notes.
- Package-only: the final master commit must contain `[artifact only]`; no update-server publication, GitHub release, or service deployment is requested.
- Production certificate SHA-256 remains `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`.

## Included changes

All ten fixes from `audit/ycore-fixes-20261006/README.md`: PCM timing and partial writes, dynamic software-audio formats, bounded Surface shutdown, TTML timing/XML, SAMI scalability/cancellation, separated HLS adaptation, HDR10+ per-picture metadata, spatial bob deinterlacing, remote/nested Matroska chapters, and float PCM output.

The source tree passed the Android/TV, native and CodeQL gates before #222 merged; emulator tests reported 57 passes and three optional external-media/soak skips. Packaging must also pass exact-commit quality gates, production certificate and APK metadata checks, and signed APK startup smoke on Android 35–37.

## Signing confirmation

At 2026-10-07 10:27:25 (Asia/Shanghai), the release owner explicitly confirmed the MDK usage/distribution rights for the existing SDK in this 1.1.4 (266) package-only delivery. `.github/mdk-distribution-approval.json` records this version, unchanged pinned SDK checksum and package-only scope. Retries retain 1.1.4 (266).

The production-signed APK still needs the package workflow and Android 35–37 startup checks before delivery.
