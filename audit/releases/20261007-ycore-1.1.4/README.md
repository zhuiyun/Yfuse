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

The package workflow and Android 35–37 startup checks completed successfully; see the delivery record below.

## Packaging regression fixture

The initial package PR's quality run (37514951912) exposed an existing scheduling-dependent assertion in `AndroidMediaExtractorReadAheadNodeTest`: 64 nonblocking polls can consume fewer than 64 samples, leaving the producer at its watermark before the test's expected read count. The regression now waits for and asserts 64 actual samples, closes the owner, then checks a single staging-buffer identity. No application behavior or signing checks are weakened.

## Verified delivery

- Package-only run [37562247809](https://github.com/zhuiyun/Yfuse/actions/runs/37562247809) completed successfully (attempt 2), from merge commit `281f31a2154575d037c231922cb673a64e6ea5a4` (#223).
- Actual APK metadata: `com.yfuse`, versionName `1.1.4`, versionCode `266`, ARM64 only. `apk-badging.txt` and `apk-signature.txt` retain the verification output.
- APK: 29,549,542 bytes, SHA-256 `f2be2d8de0c55f3cf9c8134cc16ba76395074dc046c75e372031f7be79fde89b`.
- Certificate SHA-256: `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`; one signer, APK Signature Scheme v2. The downloaded signed artifact digest matches GitHub's artifact digest; its APK digest matches both the emulator identity check and final packaging step.
- Exact-source [Android quality gates](https://github.com/zhuiyun/Yfuse/actions/runs/37562247481), [TV](https://github.com/zhuiyun/Yfuse/actions/runs/37562247531), [native](https://github.com/zhuiyun/Yfuse/actions/runs/37562247471), [Dolby regressions](https://github.com/zhuiyun/Yfuse/actions/runs/37562247496) and [CodeQL](https://github.com/zhuiyun/Yfuse/actions/runs/37562247530) passed. Instrumented tests: 60 total, 57 passed, 3 optional external-media/soak tests skipped, zero failures or errors.
- Android 15 and 16 signed-APK UI smoke passed on attempt 1. Android 17 passed all 14 cases on the unchanged APK in attempt 2, including 22 foreground/background cycles over 124.82 seconds.
- Android 17 attempt 1 did fail the foreground/background case: process exit reason ANR, waiting 5 seconds for a focus event. At that time the emulator reported 100% CPU, 85% kernel, with graphics composer, launcher and system processes prominent; an app frame took 6.179 seconds. This suggests emulator graphics/load involvement but does not establish a definitive root cause. The original failure is preserved in [artifact 11458700807](https://github.com/zhuiyun/Yfuse/actions/runs/37562247809/artifacts/11458700807). An unchanged replay passed; no timeout or test assertion was relaxed. This is not a claim that all Android 17 ANR risks have been eliminated.
- Final package artifact: [Yfuse-1.1.4](https://github.com/zhuiyun/Yfuse/actions/runs/37562247809/artifacts/11458088943), containing `Yfuse-266-1.1.4.apk` and both manifests. The user-facing copy is named `Yfuse-1.1.4-266.apk`; its bytes are identical.
- `PUBLISH_UPDATE=false`: deployment/SSH/server publication steps were skipped. No app update was published.

1.1.4 (266) is now the verified delivery. A new future delivery must increment both version fields; retries of this delivery retain them. These emulator checks cover unauthenticated startup/UI only; real HDR/HDMI hardware, account-backed playback and long-duration stability remain outside this package smoke.
