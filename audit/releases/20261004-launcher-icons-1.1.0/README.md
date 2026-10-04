# Five switchable launcher icons — 1.1.0 (262)

Base: cloud `master` at `eac9636317b8a62f418a75a6ca2bcb23eb68ee08` (#215), merged into branch
`ccr-42d7b5b2-pdkr1p` as `6b210b95`. On October 4 the owner asked for new logo designs, then for
all of them to be switchable in the settings with larger marks ("全部做到设置里面自己切换，logo 元素要
大一点，特别是 2 号"), then for a formal package following `AGENTS.md`.

## Version

- Last APK actually delivered: 1.0.99 (261), the owner's separate delivery, from package-only run
  [37178647914](https://github.com/zhuiyun/Yfuse/actions/runs/37178647914) of #215's merge, which
  finished while this one was being prepared: `Yfuse-261-1.0.99.apk`, 29,390,921 bytes, SHA-256
  `0bf6e2e4…676ecf`, `PUBLISH_UPDATE=false`, signed, and through the Android 35–37 smoke. Before it,
  1.0.98 (260) from run [37110381511](https://github.com/zhuiyun/Yfuse/actions/runs/37110381511).
- This delivery is therefore code 262, above both. Offered 1.0.100 (262) or 1.1.0 (262), the owner
  chose 1.1.0 (262). `version.properties` and `release-notes.txt` carry it; every earlier version's
  notes are kept.

## Scope

- `AppIconVariant` gains `Prism` (汇光), `WaterOverFire` (水火既济), `Overprint` (叠印), `Danmaku`
  (弹幕) and `LiquidGlass` (液态), listed after the existing five in Logo 与开屏动画 → APP 图标.
- Each is an adaptive icon with background, foreground and monochrome vector layers
  (`res/drawable/ic_<key>_*.xml`, `res/mipmap-anydpi/ic_launcher_<key>.xml`) behind an
  `activity-alias` that is disabled in the manifest; switching uses the existing path unchanged.
- The settings preview and the home header draw the two vector layers at the launcher's crop.
- `scripts/launcher_icons/generate.py` draws the layers and the SVG masters in
  `docs/logo-concepts-20261004/`; `docs/LOGO_CONCEPTS_20261004.md` describes the designs.
- The marks reach 411–461 units from the centre of the 1024 canvas (the current icon: 444; the 66dp
  safe zone: 469). 水火既济 grew 1.65x from its first draft.

## Verification before the pull request

This workspace cannot reach Google Maven (`dl.google.com` is refused by its network policy), so the
Android code was not compiled here.

- ktlint 1.3.1 on every changed Kotlin file: 0 violations; a deliberate violation is reported.
- `scripts/release_metadata.py`: 1.1.0 (262) with its notes.
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`: 65 passed, including
  `test_launcher_icons.py`, which fails when an alias is missing (checked by renaming one).
- `python3 -m unittest discover --start-directory scripts --pattern 'test_*.py'`: 52 passed.
- `check_function_size.py`, `verify-module-boundaries.py`, `verify-tv-source.py`,
  `git diff --check`: passed.
- The fifteen vector drawables parse; every `pathData` is at most 769 characters (lint's VectorPath
  limit is 800). Rendered back to SVG they match the masters within 1.1% RMSE.
- On October 4 at 14:18 (Asia/Shanghai) the release owner explicitly confirmed use and distribution
  of the existing MDK SDK for this package-only delivery, chose 1.1.0 (262), and asked for a pull
  request that Claude merges into `master` as `[artifact only]` once 1.0.99 (261) has come out,
  which it now has.
  `.github/mdk-distribution-approval.json` records it with the SDK checksum and scope unchanged;
  `scripts/mdk_distribution_approval.py --package-only` returns `true`, and `false` without it.

## Delivery

All PR #216 checks passed on `52e6ccc1`; Android quality gates run
[37182501795](https://github.com/zhuiyun/Yfuse/actions/runs/37182501795) compiled, ran the unit and
instrumented tests, lint, the release DEX check and an R8 package of 29,426,394 bytes. The PR was
merged as `73f7370e0b4aff15d6ce37b116266104862a671b` with `[artifact only]`, and the push started
package-only run [37183767245](https://github.com/zhuiyun/Yfuse/actions/runs/37183767245).

- On the merge commit, Android quality gates
  [37183767078](https://github.com/zhuiyun/Yfuse/actions/runs/37183767078), TV quality gates
  [37183767111](https://github.com/zhuiyun/Yfuse/actions/runs/37183767111), CodeQL
  [37183767143](https://github.com/zhuiyun/Yfuse/actions/runs/37183767143) and the Dolby validation
  [37183767092](https://github.com/zhuiyun/Yfuse/actions/runs/37183767092) passed. The packaging
  run waited for them, and its request job accepted 1.1.0 (262) as newer than 1.0.99 (261).
- The production signature's check passed. It requires package `com.yfuse`, `versionCode='262'`,
  `versionName='1.1.0'`, one signer, APK Signature Scheme v2 only, certificate SHA-256
  `373e36d3…e7be3e84` and the 30,000,000-byte budget.
- The startup smoke passed all 14 cases on Android 35, 36 and 37 emulators. Each run read the APK
  itself: SHA-256 `66eb8a8f…80846d`, certificate `373e36d3…e7be3e84`, package `com.yfuse`, 262 and
  1.1.0, ABI `arm64-v8a`.
- Final APK: `Yfuse-262-1.1.0.apk`, 29,413,982 bytes (23,061 more than 1.0.99), SHA-256
  `66eb8a8f25cac36c10371eb2ed4182b2bc3fe4bb80bc4037d584836aea80846d`, `PUBLISH_UPDATE=false`.
  Artifact `Yfuse-1.1.0`
  ([11296193616](https://github.com/zhuiyun/Yfuse/actions/runs/37183767245/artifacts/11296193616))
  holds it with `update.json` and `update-v2.json` for 7 days.
- Nothing was published. The deploy job skipped its SSH, upload, server-check and finalize steps.
  "Create GitHub release" run [37185287105](https://github.com/zhuiyun/Yfuse/actions/runs/37185287105)
  found no published update and skipped its release job, so there is no `v1.1.0` release. Firebase
  Test Lab run [37185287070](https://github.com/zhuiyun/Yfuse/actions/runs/37185287070) skipped
  signing in and both device jobs.
- Not run on any device: switching to the new icons. The smoke does not open APP 图标, and no
  instrumented test switches launcher icons; `test_launcher_icons.py` checks the manifest wiring
  statically.
- 1.1.0 (262) is now the last delivered APK, so the next delivery needs code 263 or higher.

Package-only delivery is intended. Do not publish an application update, create a release or
deploy a service as part of this build.
