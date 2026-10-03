# Player VerifyError root fix and short-drama work — 1.0.98 (260)

Base: cloud `master` at `c4d12e24d4c5edfddf85bc39e6d6aee9a3b123d1`, the 1.0.97 (259) package.
On October 3 the owner sent `Yfuse-diagnostics-20261003-100314.zip`: 1.0.97 (259) threw
`java.lang.VerifyError` as the player opened. The owner asked for the cause, then for the root fix
and a new signed package, and then for the short-drama work to ship in the same package.

## Scope

- Cause (`docs/diagnostics-20261003-player-verifyerror.md`): R8 allocates registers for a method
  of more than 256 on a separate path, and there it overwrote a copy of a parameter still in use in
  PlayerRoot's runtime lambda. ART rejected `PlayerRootKt`. R8 9.1.31 through 9.4.20 all do it.
- Root fix: no method of the app's own code needs more than 256 registers. The three that did, in
  both the phone and the TV package, are split with their bodies moved verbatim:
  - PlayerRoot's runtime lambda (308 registers) is an extension of `PlayerRuntimeSession`;
  - `PlayerControls` (272) is an extension of `PlayerControlsInputs`;
  - `AndroidAdaptiveCore2YPlayer.runLoop` (289) runs its command loop in a local class.
- Release check: `scripts/verify-release-dex.sh` rejects code ART would not verify and, with the R8
  mapping, any app method over 256 registers. The quality, TV and packaging workflows and
  `build-release-packages.ps1` run it on every R8 release package.
- Short-drama work: branch `ccr-9a50929d-07i3hs` at `4f72c08f` (32 commits on `c4d12e24`, reviewed in
  `docs/SHORT_DRAMA_SUPPORT_REVIEW_20261002.md`), merged in `5c9a34b2`. It changed the three split
  files, so the merge took its versions and `2dcb7bce` applied the same split to them again. The
  release notes list what it brings.

## Verification

- Previous delivery: packaging run [36862579231](https://github.com/zhuiyun/Yfuse/actions/runs/36862579231)
  (#140) on `c4d12e24`. Its log records:
  - `Yfuse-259-1.0.97.apk`, `1.0.97`, `259`;
  - 29,844,919 bytes, SHA-256 `f063d9d8…94030574`;
  - `PUBLISH_UPDATE=false`;
  - a passing APK metadata and signing-certificate step.
- New version configuration and release notes: `1.0.98 (260)`, historical notes retained.
  - `python3 scripts/release_metadata.py`: passed.
  - `python3 -m unittest discover --start-directory scripts --pattern 'test_*.py'`: 51 tests passed.
- On October 3 at 14:11 (Asia/Shanghai) the release owner explicitly confirmed use and distribution
  of the existing MDK SDK for this package-only 1.0.98 (260) delivery, and agreed to opening a pull
  request and merging it into `master` as `[artifact only]`. `.github/mdk-distribution-approval.json`
  records it without changing the SDK checksum or the approval scope;
  `scripts/mdk_distribution_approval.py --package-only` returns `true`.
- Split build, run [37104094707](https://github.com/zhuiyun/Yfuse/actions/runs/37104094707) on
  `fcaef332` (R8 9.1.31, debug-signed release builds):
  - ktlint on `composeApp` and `tvShared`: passed;
  - release DEX check with the R8 mappings: 0 findings in the phone package (58,498 methods) and the
    TV package (44,543 methods); the largest app methods have 188 and 189 registers, where 1.0.97
    had 308 (PlayerRoot's runtime), 289 (`runLoop`) and 272 (`PlayerControls`);
  - ART on an Android 16 (API 36) emulator, verifying the phone package from scratch: no rejected
    method.
- Merged build with the short-drama work, run
  [37107401799](https://github.com/zhuiyun/Yfuse/actions/runs/37107401799) on `476eeba4`:
  - ktlint: passed;
  - release DEX check: 0 findings in the phone package (58,752 methods) and the TV package (44,855
    methods); the largest app method in both is `PlayerControls` with 194 registers;
  - ART on the Android 16 emulator: no rejected method.
- Pull-request checks on `476eeba4`, all passed: the phone quality gates (R8 release package
  29,836,071 bytes against the 30,000,000-byte budget), the TV quality gates, YCore, CodeQL, the
  release scripts and the dependency gate.
- The short-drama changes have not been tried on a device; their review says so, and nothing here
  could run them against a media server.
- Local Android execution is unavailable: this workspace has no Android SDK and `dl.google.com` is
  blocked.
- Pending:
  - the phone and TV quality gates on the pull request and on the merge commit;
  - production signing.

Package-only delivery is intended. Do not publish an application update as part of this build.
