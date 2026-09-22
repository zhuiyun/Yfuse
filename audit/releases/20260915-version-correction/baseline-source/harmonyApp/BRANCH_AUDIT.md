# Harmony branch audit

- Original development branch: `feature/harmony-cangjie`. It still exists locally and on `origin`.
- `harmonyApp` was merged into `master` at `4db30a08` (2026-08-24) and now lives there. The earlier
  statement that this work "remains separate from `master`" is no longer true.
- Android parity baseline: `45d39439` (2026-08-24). `master` is 779 commits ahead of it, so the
  parity contracts describe an older Android product than the one now shipping.
- Scope: `harmonyApp`, `parity`, `ycore-native`, the Harmony native dependency contract and the
  Harmony verification scripts and workflow.
- Android application sources and Android release metadata are not modified by the Harmony work.
- Private signing material, local SDK paths, generated HAP files and compiled native libraries are
  excluded from source control.

Being on `master` does not mean this module is releasable. As of 2026-09-10, eight pure Cangjie
packages compile on the Windows host and 33 host tests pass. The Emby/Jellyfin user flow is wired
in source, but no HAP has been compiled or run because the matching DevEco Cangjie SDK is absent. See
`PORT_STATUS.md` for what exists and `../parity/implementation-coverage.json` for per-feature status.
Nothing may ship until the gates in `RELEASE_CHECKLIST.md` have captured evidence.
