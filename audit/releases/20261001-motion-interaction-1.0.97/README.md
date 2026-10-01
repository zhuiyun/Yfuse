# Motion and interaction fixes — 1.0.97 (259)

Base: cloud `master` at `a2a7deda07abe028c776b58987acbceaa8e76bbb`, the 1.0.96 (258) package.
The owner asked to fix every finding of the 1.0.96 motion and interaction review, to remove the
player's side long-press fast-scan (×10 / ×30 / ×60), and then to package the result as a signed
build.

## Scope

- The review's P0 (TV airing calendar crash on a repeated LazyRow key), all P1, P2 and P3 findings,
  in 20 commits on `ccr-413ccca2-jd1ckj`. Three further crashes found on the way were also fixed:
  - a TV calendar card without a TMDB id;
  - a 稍后观看 playlist listing a title twice on the TV rows;
  - the phone calendar following a show TMDB has not identified.
- The side-thirds long-press hold-scan and its gears are removed (`PlayerHoldScan.kt` and its test
  deleted, gesture help and the interaction design document updated). The middle-third 2× hold,
  double-tap ±10 s, the horizontal scrub and the TV remote's held fast-forward stay.
- New `com.yfuse.AppEntry` activity-alias, never disabled, for shortcuts, the widget,
  notifications and `yfuse://watch` links. The launcher-icon switch waits until the user has left
  the app.

## Verification

- Previous delivery: packaging run [36695042729](https://github.com/zhuiyun/Yfuse/actions/runs/36695042729)
  (#139) on `a2a7deda`. Its log records:
  - `Yfuse-258-1.0.96.apk`, `1.0.96`, `258`;
  - 29,818,151 bytes, SHA-256 `58f64f00…6105ae02e`;
  - `PUBLISH_UPDATE=false`;
  - a passing APK metadata and signing-certificate step.
- New version configuration and release notes: `1.0.97 (259)`, historical notes retained.
  - `python3 scripts/release_metadata.py`: passed.
  - `scripts/test_release_metadata.py`: passed.
- Android TV quality gates, which compile all shared phone and TV sources:
  - run [36822196168](https://github.com/zhuiyun/Yfuse/actions/runs/36822196168) found one compile
    error (`AppTask.taskInfo` is nullable on compileSdk 37), fixed in `981a8fd8`;
  - runs [36822667249](https://github.com/zhuiyun/Yfuse/actions/runs/36822667249) and
    [36824017261](https://github.com/zhuiyun/Yfuse/actions/runs/36824017261) passed. Each covered
    ktlint, compilation, the TV unit tests, debug and R8 release builds, lint-vital and the
    packaged TV contract. The second ran on the branch head `29921db3`.
- Local checks passed:
  - ktlint 1.3.1 on every changed Kotlin file;
  - a port of `verifyDesignSystemUsage` and `verifyBehavioralTestBoundaries`, which catches
    planted violations;
  - the `fast-contracts` scripts and node tests;
  - `git diff --check`;
  - the cloud UI script tests.
- Local Android execution is unavailable: this workspace has no Android SDK and `dl.google.com` is
  blocked.
- Pending:
  - the phone Android quality gates (unit tests, lint, R8 size budget) on the pull request and on
    the merge commit;
  - the release owner's MDK confirmation for this package-only version;
  - production signing.

Package-only delivery is intended. Do not publish an application update as part of this build.
