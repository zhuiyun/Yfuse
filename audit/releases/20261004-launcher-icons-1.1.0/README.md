# Five switchable launcher icons — 1.1.0 (262)

Base: cloud `master` at `eac9636317b8a62f418a75a6ca2bcb23eb68ee08` (#215), merged into branch
`ccr-42d7b5b2-pdkr1p` as `6b210b95`. On October 4 the owner asked for new logo designs, then for
all of them to be switchable in the settings with larger marks ("全部做到设置里面自己切换，logo 元素要
大一点，特别是 2 号"), then for a formal package following `AGENTS.md`.

## Version

- Last APK actually delivered: 1.0.98 (260), packaging run
  [37110381511](https://github.com/zhuiyun/Yfuse/actions/runs/37110381511), `Yfuse-260-1.0.98.apk`,
  29,836,199 bytes.
- 1.0.99 (261) has not come out yet: runs 37122566505 and 37128629545 failed (the signer check, then
  the Android 15 smoke), and package-only run
  [37178647914](https://github.com/zhuiyun/Yfuse/actions/runs/37178647914) of #215's merge was in
  progress when this record was written. It is the owner's separate delivery and keeps its version.
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

## Verification so far

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
  request that Claude merges into `master` as `[artifact only]` once 1.0.99 (261) has come out.
  `.github/mdk-distribution-approval.json` records it with the SDK checksum and scope unchanged;
  `scripts/mdk_distribution_approval.py --package-only` returns `true`, and `false` without it.

## Pending

- The pull request's quality gates (compile, unit and instrumented tests, lint, R8 package, DEX
  check, size budget), the packaging run's production signing and its Android 35–37 startup smoke.
- Reading the final APK's package name, version name and code, size, SHA-256 and signing
  certificate.

Package-only delivery is intended. Do not publish an application update, create a release or
deploy a service as part of this build.
