# Calendar card, #211, the DLNA relay and Test Lab merged — 1.0.99 (261)

Base: cloud `master` at `eedf26a48eaf7815f124b7849f3a7205bdaa13a6`, the 1.0.98 (260) package.
On October 3 the owner asked for the per-show 播出日历 to become a month-grid card, then to merge
all code and package it ("合并所有代码打包"). Asked what "all" covers, the owner chose to include
#211; the DLNA relay and the Test Lab workflow, both finished and unmerged, came along with it.

## Scope

- 播出日历 card, branch `ccr-afc1c9d3-phenca` at `58124526`: the detail page's and 追剧日历's
  per-show calendar is a card with the poster, title, season and progress, synopsis and score, a
  Sunday-first month grid with brush strokes under broadcast days (neighbouring days share one),
  episode ranges under the dates, today marked, and the media library's holdings in the foot.
  Pure logic in `ShowSchedule.kt` with `ShowScheduleTest` (11 tests).
- #211 (`audit/quality-completeness-20261002` at `3ccaa037`, which contains #204): the P0–P2
  review work — WebDAV/SMB/Alist file sources, opening videos from other apps, the TMDB proxy
  with a direct fallback, the PlayerRoot / PlayerControls / runLoop / PlayerStore splits, one
  fallback ladder for the engines, download completion checked against the full Content-Range,
  the Trakt and SecureStore capacity bounds, and a release workflow split into request, gates,
  build, sign, smoke and deploy jobs. Merged in `5cbfcf0e`. Where #211 restructured code that
  1.0.98 had changed, #211's structure is kept and 1.0.98's behaviour is carried into it; the
  merge commit lists each place. #211's simpler portrait-video orientation, which duplicated
  1.0.98's per-entry orientation, is dropped in favour of 1.0.98's.
- DLNA relay, branch `ccr-32892c19-ioep1e` at `6fd2fd6e`, merged in `bf674fb6`: a DLNA cast of
  media on an HTTPS or non-LAN server goes through the phone; its player-side lines moved into
  #211's split files.
- Firebase Test Lab, branch `ccr-413ccca2-jd1ckj` at `c49a6c63`, merged in `e8475566`: after a
  successful packaging run it tests the signed APK on a real phone, and skips itself until the
  `FIREBASE_TEST_LAB_SERVICE_ACCOUNT` secret exists.

## Verification

- Previous delivery: packaging run [37110381511](https://github.com/zhuiyun/Yfuse/actions/runs/37110381511)
  (#141) on `eedf26a4`. Its log records:
  - `Yfuse-260-1.0.98.apk`, `1.0.98`, `260`;
  - 29,836,199 bytes, SHA-256 `b56d19a6…80b3fb2a`;
  - `PUBLISH_UPDATE=false`;
  - a passing APK metadata and signing-certificate step.
- New version configuration and release notes: `1.0.99 (261)`, historical notes retained.
  - `python3 scripts/release_metadata.py`: passed.
  - `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`: 63 tests passed.
- On October 3 at 17:57 (Asia/Shanghai) the release owner explicitly confirmed use and distribution
  of the existing MDK SDK for this package-only 1.0.99 (261) delivery, and agreed to opening a pull
  request and merging it into `master` as `[artifact only]`. `.github/mdk-distribution-approval.json`
  records it without changing the SDK checksum or the approval scope;
  `scripts/mdk_distribution_approval.py --package-only` returns `true`, and `false` without it.
- Local checks on the merged tree (this workspace has no Android SDK, so androidMain and the TV
  app were reviewed rather than compiled):
  - ktlint 1.3.1 with no baselines on every Kotlin file the merges touched: 0 violations;
  - `scripts/check_function_size.py`: passed; a Python port of `verifyDesignSystemUsage`, including
    #211's motion rules: 0 violations; `verify-module-boundaries.py`, `verify-tv-source.py` and the
    Node renderer tests: passed;
  - `commonMain` and `commonTest` type-checked against Compose Multiplatform 1.8.2 on the JVM: no
    errors outside the known 1.8-to-1.12 API gap and the Turbine test dependency the check leaves
    out;
  - `PlayerGestureStateTest` and `PlayerChromeStateTest` run on the JVM: 24 tests passed, including
    the new ones for 上下滑换集 and 还在看吗 in #211's split state holders.
- For comparison: #211's own quality gates on `3ccaa037` built a 29,332,533-byte R8 package.
- Pull-request checks on `317593fe`, all passed:
  - phone quality gates, run [37117833765](https://github.com/zhuiyun/Yfuse/actions/runs/37117833765):
    ktlint with no baselines, compile and unit tests, the instrumented tests on an Android 15
    emulator, the design rules, Android lint against the committed baselines with no new finding,
    the dependency locks, the release DEX check, and an R8 release package of 29,404,589 bytes
    against the 30,000,000-byte budget;
  - TV quality gates, run [37117833805](https://github.com/zhuiyun/Yfuse/actions/runs/37117833805);
  - YCore [37117833763](https://github.com/zhuiyun/Yfuse/actions/runs/37117833763) and CodeQL
    [37117833786](https://github.com/zhuiyun/Yfuse/actions/runs/37117833786).
- Merged build, run [37117830528](https://github.com/zhuiyun/Yfuse/actions/runs/37117830528) on
  `317593fe` (R8 9.1.31, debug-signed release builds; the temporary workflow 1.0.98 used, removed
  again before the merge):
  - release DEX check: 0 findings in the phone package (61,344 methods) and the TV package (52,500
    methods); the largest app methods have 206 and 207 registers (`PlayerRootControls`), then
    PlayerRoot's runtime lambda with 203 and 205 and `PlayerControls` with 194 and 191;
  - ART on an Android 16 (API 36) emulator, verifying the phone package from scratch: no rejected
    method.
  - Its first run, on `ad8175f5`, ran out of memory compiling both shared modules side by side in
    the 4 GB Kotlin daemon; `317593fe` compiles them in one process first, as the quality gates do.
- Not tried on a device: the merged player, the DLNA relay against a real television, and #211's
  features. Server-side parts of #211 (the TMDB proxy route, signing in to the television with the
  phone) need a watchTogetherServer deployment, which this delivery does not include; without it the
  app reads TMDB directly with the built-in token, as before.
## After the merge

PR #213 was merged as `2ef2c00a9bfcebcadb08fa2cc4be859b8a4be639` with `[artifact only]`, and the
push started packaging run [37122566505](https://github.com/zhuiyun/Yfuse/actions/runs/37122566505).
No APK came out of it, and nothing was published.

- TV quality gates [37122566224](https://github.com/zhuiyun/Yfuse/actions/runs/37122566224), YCore
  [37122566221](https://github.com/zhuiyun/Yfuse/actions/runs/37122566221), CodeQL
  [37122566233](https://github.com/zhuiyun/Yfuse/actions/runs/37122566233) and the Dolby validation
  [37122566239](https://github.com/zhuiyun/Yfuse/actions/runs/37122566239) passed on the merge commit.
- Phone quality gates [37122566259](https://github.com/zhuiyun/Yfuse/actions/runs/37122566259): every
  job passed except the instrumented tests, where `SearchVisibleMotionInstrumentedTest` failed with
  "Reduced motion left an animated decoration" (58 run, 1 failed, 3 skipped). The packaging run's
  gate therefore refused the commit before signing.
  - Cause, in the test: each iteration switches the theme, slept 150 ms and captured the still field
    that every later frame is compared with. Every glass plate crossfades its fill and edge for
    `Motion.THEME_CROSSFADE` (380 ms), so in the dark iteration that reference could hold part of the
    light plate. With 减少动态效果 on the field draws no highlight at all, so no settled frame could
    match it. The same tree had passed on `f97d34b3` and `317593fe`, and the test failed the same way
    once before, on `2e546697`.
  - The failed job was re-run once (attempt 2) and passed. The test now captures the still field
    after the crossfade.
- Packaging run, attempt 2: the gate passed and the build job's release APK (attempt 1, with the
  DEX check passing) was signed with the production key, but "Verify APK metadata and signing
  certificate" refused it. #211's split workflow reads the certificate only from a
  `Signer #1 certificate SHA-256 digest:` line; the runner's newest apksigner prints the signer per
  scheme, `V2 Signer: certificate SHA-256 digest: 373e36d3…e7be3e84`, which is the pinned
  certificate. The check found no digest and failed closed; the smoke test and deployment were
  skipped. `signature-full.txt` here is that output, and `test_publish_job_split.py` now checks both
  formats. The check also refuses any second certificate digest, under either name.
- Next: a package-only run (`publish=false`) of the follow-up's merge commit, version unchanged at
  1.0.99 (261) as a retry of this delivery. Still pending: production signing, the signed-APK
  startup smoke on Android 35–37, and reading the final APK's package name, version, size, SHA-256
  and signing certificate.

## After the follow-up merge

PR #214 was merged as `caef4f991ed33404f4d171e85b9f753fba6fe7ac` with `[artifact only]`, and
package-only run [37128629545](https://github.com/zhuiyun/Yfuse/actions/runs/37128629545)
(`publish=false`, 1.0.99 (261)) packaged it. Nothing was published.

- The quality gate, the release build with its DEX check, production signing and the certificate
  check passed. The signed APK's SHA-256 was `e06d261f…5729f195`, certificate `373e36d3…e7be3e84`.
- Signed-APK startup smoke: Android 36 and 37 passed. Android 35 failed in "Short
  foreground/background stability", in attempt 1 and in the one re-run of that job (attempt 2), so
  the deployment job was skipped. The emulator froze: the guest log stopped for every process at
  once, adb timed out, and the emulator process exited about a minute later. Yfuse logged no crash.
- A temporary workflow on `ccr-afc1c9d3-phenca` ran the gate's smoke on that signed APK under one
  change at a time, in runs [37161859004](https://github.com/zhuiyun/Yfuse/actions/runs/37161859004),
  [37162851486](https://github.com/zhuiyun/Yfuse/actions/runs/37162851486),
  [37163604416](https://github.com/zhuiyun/Yfuse/actions/runs/37163604416),
  [37171229170](https://github.com/zhuiyun/Yfuse/actions/runs/37171229170),
  [37172114425](https://github.com/zhuiyun/Yfuse/actions/runs/37172114425) and
  [37173305918](https://github.com/zhuiyun/Yfuse/actions/runs/37173305918); it was removed before
  the fix. Soaks passed, by arm:
  - unchanged: 4 of 13, counting the two gate attempts;
  - display settings restored before the soak, so no rotations: 4 of 4 (a fifth run failed earlier,
    at app launch);
  - the system Settings app instead of Yfuse, through the same landscape soak: 3 of 3;
  - Yfuse on 我的 instead of 首页: 3 of 3;
  - Yfuse drawn as 静息 (thermal status MODERATE, confirmed before and after; the live glass off):
    6 of 6, against 0 of 2 unchanged controls in the same run;
  - with the live glass still on: app animators off 2 of 3, window and rotation animations off 1 of
    3, guest Vulkan off 3 of 6; 6 GB of RAM, 4 cores and guest rendering each failed.
- Cause: the soak inherited the landscape, 1.3 font and dark theme of the cases before it, so every
  cycle rotated the display between the portrait launcher and Yfuse's landscape 首页. The live glass
  under the dock and the bars captures and blurs the page every frame; on the Android 15 image's
  software GPU such frames took one to two seconds, and a rotation among them froze the emulator.
- The owner chose to change the gate, not the app: the smoke now puts back the device's own font,
  rotation and theme before the soak ("Display settings restored before the soak"). Landscape,
  dark and large font keep their own capture cases. Yfuse and its version are unchanged.
- This fix is PR #215. All of its checks passed on `377ca5af`, including the instrumented tests on
  the Android 15 emulator, and it was merged as `eac9636317b8a62f418a75a6ca2bcb23eb68ee08` with
  `[artifact only]`.

## Package

Package-only run [37178647914](https://github.com/zhuiyun/Yfuse/actions/runs/37178647914) of
`eac96363` (`publish=false`, 1.0.99 (261), the same release notes as the earlier attempts)
passed every job:

- the quality gate on the merge commit;
- the release build with its DEX check, the version-metadata check and the APK-contents check;
- production signing, then the metadata and signing-certificate check: package `com.yfuse`,
  versionCode 261, versionName 1.0.99, exactly one signer with the pinned certificate, v2 only, and
  the size within the 30,000,000-byte budget;
- the signed-APK startup smoke on Android 35, 36 and 37. On Android 35 every case passed:
  "Display settings restored before the soak" left the display in portrait (1080×1920), and the
  soak ran 22 cycles in 123 s.

The final APK:

- `Yfuse-261-1.0.99.apk`, package `com.yfuse`, versionName `1.0.99`, versionCode `261`.
  - Each smoke job's identity case checks the package, both version fields, the SHA-256 and the
    certificate of this exact APK.
  - 1.0.98 (260) was the previous delivery.
- 29,390,921 bytes, SHA-256 `0bf6e2e48710c42e53daf035acc87f09acfb5b107a3c5a5a7517cd104a676ecf`.
- Signing certificate SHA-256 `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`,
  v2 scheme only.
- Kept as the run's `Yfuse-1.0.99` artifact (the APK with `update.json` and `update-v2.json`),
  until 2026-10-11.

`PUBLISH_UPDATE` was `false`. The SSH, publish, server-verification, release-finalization and
rollback steps were skipped, and no GitHub release was created.

`version.properties` and `release-notes.txt` have not changed since the 1.0.99 (261) preparation,
where `scripts/release_metadata.py` passed. #214 and #215 did not touch them.


Package-only delivery is intended. Do not publish an application update, create a release or
deploy a service as part of this build.
