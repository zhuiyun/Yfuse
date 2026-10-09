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
