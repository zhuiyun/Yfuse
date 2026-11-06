> 历史分支记录：下文仅描述 `origin/improve/senplayer-engine-and-plan-20261105` 在 2026-11-05 的状态，不代表合并后的功能或验证状态。2026-11-06 已整合正式 `senplayerBridge`，移除该分支重复的 `senPlayerAndroid`、替代缓冲契约以及独立 MPV/MDK/Exo 实现。原分支的完整 49 文件已归档，参见 [双内核合并说明](TWO_ENGINE_MERGE_20261106.md)。

# SenPlayer / Yfuse implementation progress — 2026-11-05

Base: master `80fac8caab99a4db9c491fb9ac9edf1c758f7cc1`. Earlier comparison fixes were merged in PR #267.
The subsequent master calendar/metadata and playback-overlay changes are preserved.

This record distinguishes implemented source from validated behavior. The 167 feature rows in the
feature/UI comparison include existing capabilities, not 167 new features to add. This branch is a
draft and does not claim that the full comparison plan or SenPlayer runtime integration is complete.

| Work | Source in this branch | Remaining acceptance |
| --- | --- | --- |
| SenPlayer selection | Android bridge module, manual engine mode, factory/surface integration, bundled/ABI checks, importer | Full Android compile and ARM64 playback; independent runtime/license initialization |
| M0 Baseline / UI evidence | Comparison scope and implementation state recorded here | Current phone/tablet/TV captures; no screenshot-based visual pass claimed |
| M1 Playback / weak network | Actual cache interval model and rail rendering; confirmed input progress can extend MPV startup past its old hard deadline | Physical-device weak network/retry cases; multi-range telemetry for other engines; memory budget validation |
| M2 Progress / navigation | PR #267 explicit-seek timeline correction retained | Cross-page and cross-device convergence, back/rotation verification |
| M3 Player / adaptive UI | Cache holes preserved in the seek rail; latest master's compact overlays retained | Portrait/landscape/tablet/foldable measurements and gestures |
| M4 Unified search | PR #267 stale-search isolation retained | Unified ranking/results and UI acceptance |
| M5 NAS / media matching | PR #267 scan ownership and partial-result retention retained | Durable checkpointing, manual correction, protocol/account fixtures |
| M6 Subtitle workflow | PR #267 private imported files retained | Preview, saved style consistency and provider validation |
| M7 Downloads / task center | PR #267 batch deduplication, failure isolation and counts retained | Per-item failures, explicit retry-failed UI, task detail consistency |
| M8 Design / settings | Experimental playback selection and capability copy added | Whole-app settings/design/accessibility review with screenshots |
| M9 Calendar / cast / TV / together | Latest master's work preserved | Real endpoints/accounts/receivers and physical output validation |
| M10 Cloud / IPTV | No new implementation claimed | Provider-specific transports/authentication and source fixtures |
| M11 Optional privacy / AI / Wi-Fi transfer / capture | No new implementation claimed | Concrete provider/product scope and runtime validation |

## Verification so far

- The supplied ARM64 libraries passed the importer's pinned size/SHA-256/ELF checks locally.
- Importer tests reject mismatched binaries without overwriting existing files and reject unrelated ZIP entries.
- Function-size, phone/TV presentation boundaries and backend transport boundaries passed locally.
- Added tests for engine selection, cache interval normalization and startup-progress/timeout behavior.
  These Kotlin tests require the project's Gradle toolchain; do not count added tests as executed tests.
- Local Gradle bootstrap failed with `Network is unreachable` downloading the pinned Gradle distribution.
  No Android SDK or ARM64 device was available locally. PR CI is required for compilation and Kotlin tests;
  that still cannot substitute for the ARM64 runtime checks in `senPlayerAndroid/README.md`.
