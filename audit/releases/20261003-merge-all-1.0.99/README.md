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
- Not tried on a device: the merged player, the DLNA relay against a real television, and #211's
  features. Server-side parts of #211 (the TMDB proxy route, signing in to the television with the
  phone) need a watchTogetherServer deployment, which this delivery does not include; without it the
  app reads TMDB directly with the built-in token, as before.
- Pending:
  - the phone and TV quality gates on the pull request and on the merge commit;
  - production signing, the signed-APK startup smoke on Android 35–37, and reading the final APK's
    package name, version, size, SHA-256 and signing certificate.

Package-only delivery is intended. Do not publish an application update, create a release or
deploy a service as part of this build.
