# Harmony branch audit

- Development branch: `feature/harmony-cangjie`.
- Android baseline: `origin/master` at `45d39439`.
- Scope: `harmonyApp`, `parity`, `ycore-native`, the Harmony native dependency contract and the
  Harmony verification scripts/workflow.
- Android application sources and Android release metadata are unchanged by this branch.
- Private signing material, local SDK paths, generated HAP files and compiled native libraries are
  excluded from source control.

The branch remains separate from `master` until a matching DevEco Cangjie SDK can compile it and the
production signing, UI parity and physical-device gates in `RELEASE_CHECKLIST.md` have evidence.
