# 个人内容 sync — package request 1.1.5 (267)

The owner requested a signed package of the cloud-sync fixes developed on `claude/new-session-hwbjtr`.
Base: `281f31a2154575d037c231922cb673a64e6ea5a4` on `master` (the 1.1.4 package merge).

## Version and scope

- Last delivered APK: 1.1.4 (266), `Yfuse-266-1.1.4.apk`, verified in package-only run [37562247809](https://github.com/zhuiyun/Yfuse/actions/runs/37562247809).
- Previous APK SHA-256: `f2be2d8de0c55f3cf9c8134cc16ba76395074dc046c75e372031f7be79fde89b`, 29,549,542 bytes.
- New delivery: 1.1.5 (267). Both `version.properties` and `release-notes.txt` have been updated, retaining historical notes.
- Package-only: the final master commit must contain `[artifact only]`; no update-server publication, GitHub release, or service deployment is requested.
- Production certificate SHA-256 remains `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`.

## Included changes

- `7ee17e9b`: the encrypted sync document is gzip-compressed inside its AES-GCM ciphertext when its JSON exceeds the server's 256 KiB limit, and stays the plain JSON earlier builds read when it fits; restore inflates it within an 8 MiB bound. With 个人清单 in the document, 上传本机 and 立即同步 failed with 「同步数据过大，请减少弹幕绑定或服务器数量」 once 观看历史 passed roughly 600 episodes. A document still too large after compression now names the part that takes the most room.
- `f0f8299a`: 个人内容 (想看, 收藏, 观看历史, 追剧, 家庭资料 and the guardian PIN) merges with the cloud automatically on sign-in, on returning to the foreground and ten seconds after a change, backing off from 30 seconds to 15 minutes on failure. Playback moving an existing history entry along rides with the next merge instead of uploading every 15 seconds. An empty cloud is never filled automatically, so 清空云端 holds; servers and settings stay manual.

Once a cloud document has been stored compressed, devices on 1.1.4 or earlier cannot restore or upload over it until they update; 清空云端 still works there.

## Validation before packaging

The authoring environment's network policy blocks dl.google.com and github.com, so it could not install the Android SDK and NDK; Android compilation, unit tests and lint first run in this package PR's quality gates. Before that, the changed sources were compiled with Kotlin 2.4.20 against their real dependencies in a JVM harness, with stand-ins only for classes that need Android or Compose. 21 tests passed there, including the repository's `PersonalCloudSyncTest`, `SyncDocumentCodecTest` and `PersonalAutoSyncTest` unchanged. Five deliberate regressions were each caught by the intended test, and ktlint 1.3.1, the function size gate and the module boundary check passed. Measured on realistic documents, compression took 348 KiB to 42 KiB and, with every list near its cap, 945 KiB to 172 KiB.

## Signing confirmation

At 2026-10-07 16:52:58 (Asia/Shanghai), the release owner explicitly confirmed the MDK usage/distribution rights for the existing SDK in this 1.1.5 (267) package-only delivery. `.github/mdk-distribution-approval.json` records this version, unchanged pinned SDK checksum and package-only scope. The confirmation does not authorize publishing. Retries retain 1.1.5 (267).

Packaging must also pass exact-commit quality gates, production certificate and APK metadata checks, and signed APK startup smoke on Android 35–37.

## Verified delivery

- PR #224 passed every check on `f850196445bf0781ec99ec08f5f76c1527fd4f5f`: [Android quality gates](https://github.com/zhuiyun/Yfuse/actions/runs/37596792123) (compile, unit tests, Android 15 instrumented tests, lint, ktlint, design contracts), [TV](https://github.com/zhuiyun/Yfuse/actions/runs/37596792062), [CodeQL](https://github.com/zhuiyun/Yfuse/actions/runs/37596792140), release scripts and source boundaries, protocol and server tests, and the dependency gate. It was merged as `335530f494ec30a22e66d21ac70d6a1e5750b861` with `[artifact only]`.
- Package-only run [37599783432](https://github.com/zhuiyun/Yfuse/actions/runs/37599783432) completed successfully on attempt 1: request validation with the recorded MDK confirmation, the exact merge commit's Android quality gates, the build, production signing and signed-APK startup smoke on Android 35, 36 and 37 (each passed on attempt 1).
- APK metadata and signature were checked by the workflow's sign job against the signed APK itself: `aapt dump badging` reported `com.yfuse`, versionCode `267`, versionName `1.1.5`; `apksigner` reported one signer, APK Signature Scheme v2 only, certificate SHA-256 `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`; zipalign and the 30,000,000-byte budget passed. The authoring environment's network policy blocks the artifact storage host, so this record carries the workflow's verification rather than a local `apk-badging.txt` / `apk-signature.txt`.
- APK `Yfuse-267-1.1.5.apk`: 29,553,638 bytes, SHA-256 `70dffabd5219a5def411f35db1bdef46328e39d25ca37fe611b73860dd56c0be`; the final job confirmed it is the APK that was signed and smoke-tested.
- Final package artifact: [Yfuse-1.1.5](https://github.com/zhuiyun/Yfuse/actions/runs/37599783432/artifacts/11473866731) (artifact ID 11473866731, archive digest `sha256:7eabc1a1ca48bd5925f3110e459a5e0e8090d266c79fe977f12f57fb469e74bb`), containing the APK and both update manifests; retained seven days.
- `PUBLISH_UPDATE=false`: the SSH, publication, server verification and release steps were skipped. No app update was published.

1.1.5 (267) is now the verified delivery. A new future delivery must increment both version fields; retries of this delivery retain them. These emulator checks cover unauthenticated startup and UI only; account-backed sync against the live account service is not exercised by the package smoke.
