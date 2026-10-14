# Remaining branch integration — 2026-10-14

Reviewed against master `7192c010b8ef81085b6a4f0706707fa1c290efdf`. The initial inventory contained master, seven Dependabot PR branches, draft PR #225, a historical release evidence branch and a superseded gesture branch.

## Selected changes

- PR #234: Gradle 9.8.1 wrapper and verified distribution checksum. The [official release notes](https://docs.gradle.org/9.8.1/release-notes.html) identify security and dependency-resolution fixes.
- PR #235: Kotlin 2.4.21, with Gradle-generated locks for every module. The original PR failed because the runtime locks still required 2.4.20. Resolution also reconciles previously stale macrobenchmark/backend configurations with the existing catalog and security overrides.
- PR #236 / #239: pinned artifact upload/download action patch updates; existing PR checks passed.
- PR #237 / #238: update CodeQL init and analyze together. Both original PRs failed because they mixed versions. A Dependabot group keeps future CodeQL updates together.
- PR #240: Playwright 1.64.0 plus the matching container image. The original PR omitted the Docker image change and failed the existing deployment test.
- `claude/new-session-hwbjtr`: historical 1.1.5 delivery evidence. Workflow 37599783432 was independently checked: successful attempt 1, source `335530f494ec30a22e66d21ac70d6a1e5750b861`. The README explicitly identifies its statements as historical.

Sixteen independent fixes/optimizations selected from PR #225:

| Original commit | Selected behavior |
| --- | --- |
| `1da120eb5` | Show the actual invitation credential and protect the credential dialog from screenshots. |
| `4476587ad` | Give TV external playback an asynchronous-work-compatible theme, with a source gate. |
| `01bd8709c` | Open cast notifications through the stable AppEntry alias. |
| `8ca264f0d` | Read archive signing certificates on Android 9/10, with host coverage. |
| `af869c6b9` | Align BouncyCastle security overrides with the existing 1.86 catalog. |
| `05e0b010c` | Refuse shared Plex servers without a scoped token instead of sending the account token. |
| `be09b8557` | Use viewer-facing bookmark failure wording. |
| `a1beba731` | Synchronize MPV transcode ladder updates. |
| `57f76f814` | Supply optional real-time decoder hints; additionally fix the Anime4K retry order and reject non-finite rates. |
| `126e3ddfa` | Do not cache runtime resource faults as unavailable media sources. |
| `fec7b49c8` | Remove unused blocking AudioTrack write methods. |
| `2d6f255ed` | Request notification permission at the cast live-update use case. |
| `8d38e9df0` | Coalesce concurrent health probes and cache results for three seconds. |
| `a1fdf4928` | Protect metrics behind a local reverse proxy; update deployment templates without deploying them. |
| `a3eff838c` | Index batch playback-progress imports instead of repeatedly scanning the full store. |
| `da0066b99` | Adapt atomic StateFlow updates to current account/personal modules; enforce blocked-word limits inside the atomic transform. |

## Complete review findings and dispositions

Line references below refer to baseline master `7192c010b` or original draft `2a9d33cd` as stated, not the resulting line positions after integration.

1. **P1 — automatic account migration breaks older clients.** Draft `composeApp/src/commonMain/kotlin/com/yfuse/core/account/AccountRepository.kt:296,792` automatically upgrades a protocol-1 login, replacing keys and sessions. Draft `watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountService.kt:386,1341` then refuses old-client logins. Keep the migration in the draft until explicit migration behavior and a compatible client/server rollout are implemented.
2. **P1 — release-timeout behavior would undo the decoder lease protection.** Draft `composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackEngineSlot.kt:93,109,140` drops an unfinished retirement and permits another decoder. Baseline `:24,99` retains the lease and retries serial release. Do not import `cf08f103` wholesale or delete the current regression coverage.
3. **P2 — a smaller proxy route table invalidates long VOD playlists.** Draft `composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreHttpProxy.kt:2322` reduces the route limit to 8,192 while `:858,1002` evicts routes and returns 404 for already-emitted segment URLs. Keep this group pending; retain the present 50,000 limit or protect live manifest routes when adapting it.
4. **P2 — Surface-detach confirmation races pending release.** Draft `composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeDirectYPlayer.kt:172,352` acknowledges detach when `released` is set, before worker cleanup. Enhanced `:186`, Tunnel `:109`, and Adaptive `:281` have the same issue. Draft `AndroidVideoOutputDetachTest.kt:17` only uses a fake confirmation. Do not import the group until pending-release completion is tested.
5. **P2 — decoder hint rejection could silently disable Anime4K.** Original `57f76f814`, `AndroidMediaCodecVideoNode.kt:236` tries plain output before removing hints. Fixed during integration: remove optional hints first, preserve the requested filter, and never re-enable rejected hints on the plain-output retry. Added retry-order/termination tests alongside operating-rate tests.
6. **P2 — a renderer script change would omit existing tests.** Draft `watchTogetherServer/calendarRenderer/package.json:10` names only two tests. Keep the current `*.test.mjs` discovery and current network-policy syntax check; the existing deployment/security tests remain executed.
7. **Module compatibility — preserve later source splitting.** Baseline `yfuseBackendClient/src/commonMain/kotlin/com/yfuse/core/account/AccountApi.kt:27,60` and `composeApp/src/commonMain/kotlin/com/yfuse/core/account/AccountState.kt:19` own definitions that the draft places in older composeApp files. Preserve current modules and the later Application/CalendarIngestion splits; adapt small changes instead of replacing files with the draft snapshot.
8. **Gesture branch — already superseded.** Its MainActivity, RootBackFallback, PersonalCenterScreen, PersonalHomeScreen and cloud UI script/test match baseline exactly. Baseline `RootComponent.kt:51,335,348,374` includes newer Watching navigation; `PersonalContentScreen.kt:80,108,425` adds account scope resets and prevents unsupported Plex imports. Keep these later behaviors.
9. **Gesture tests — do not restore obsolete assertions.** Old `composeApp/src/androidInstrumentedTest/kotlin/com/yfuse/app/DetailBackNavigationInstrumentedTest.kt:99,105,110` requires predictive preview despite current manifest `:53` disabling it. Current test `:84` adds ordinary-back coverage and later focus/transition synchronization. The old branch also carries obsolete 1.2.5 version/MDK metadata and a forbidden 30 MB CI ceiling. It needs no new runtime merge.
10. **Historical evidence scope.** `audit/detail-return-20261012/validation.json:19` records selected host/API35 tests, not every detail test or API37 on the current commit. The added `audit/releases/20261007-personal-sync-1.1.5/README.md:40` describes a historical delivery, not the latest package baseline. Neither record substitutes for this integration's tests.
11. **Atomic constraint gap.** Original `da0066b99`, `composeApp/src/commonMain/kotlin/com/yfuse/core/data/DanmakuPreferences.kt:210` checks capacity/duplicates outside the compare-and-set operation. Move those checks into the atomic transform. This was an existing concurrency weakness, not a newly demonstrated regression.
12. **Bookmark error wording limitation.** Original `be09b8557`, `composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackBookmarkBinding.kt:47` still exposes IllegalArgumentException messages, which are not inherently a typed business rejection. No normal-save regression was found; all other storage exceptions now use the fixed message. Preserve this limitation in review scope.
13. **Change size and CI preservation.** PR225 spans 216 files and 15,226 changed lines across 59 commits, far beyond a single reviewable logical change. Its old `.github/workflows/quality-gates-v2.yml` snapshot predates current backend tests, API35/API37 tests, full MDK flags, and removal of APK size limits (baseline `:214,329`). Preserve those gates and the Dolby workflow. Land the selected independent fixes in separate commits.
14. **Dependency PR failures have concrete causes.** Kotlin `gradle/libs.versions.toml:2` requires corresponding lock updates; CodeQL `.github/workflows/codeql.yml:54,70,100,110` must use one revision; renderer `watchTogetherServer/calendarRenderer/Dockerfile:1` must match package.json. All three are corrected in this integration without weakening their gates.
15. **Small fixes verified as missing on baseline.** `composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:804,870` exposed a fixed invitation code; `composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt:308` used the account token for shared Plex servers; `tvApp/src/androidMain/AndroidManifest.xml:134` used NoDisplay for asynchronous work; `composeApp/src/androidMain/kotlin/com/yfuse/core/cast/CastRelayService.kt:109` targeted a disableable activity; `composeApp/src/androidMain/kotlin/com/yfuse/update/AppUpdateManager.kt:2115` lacked the legacy archive signature flags; `scripts/security-overrides.properties:6` overrode the catalog with an older BC release. All six are integrated as independent commits.

No LLM context construction or injected model history was changed; the code-review-context skill's Codex-specific requirements do not apply to this media application. No review comments were posted.

## Retained draft scope

PR #225 remains a draft with its unintegrated commits. The extracted fixes do not establish that the rest is safe or obsolete. Remaining groups include account protocol 2 and vault migration, watch/remote renewal and pairing, persistence/limits/PIN changes, conditional sync and unknown-field preservation, asynchronous disc-menu handling, broader player UX/audio/background behavior, TVmaze streaming parsing, renderer sandbox/network changes, and backup/systemd deployment changes.

The draft's meaningful WebSocket/SQLite tests should travel with their corresponding implementations, especially `WatchRoomGuardsTest.kt:175` and `WatchReauthenticationTest.kt:57`. Proxy admission needs its later triage fixes (`c12dc90bd`, `2a9d33cd3`); asynchronous disc menus need the matching capability correction. These are retained for coherent follow-up rather than marked merged by an `ours` merge.

## Verification

[`validation.json`](validation.json) records 5,334 passing Gradle tests (4,883 phone shared, 118 TV shared, 265 server, 43 backend client, 25 protocol), 112 script tests and 17 JavaScript tests. Phone/TV compilation, formatting of all 35 changed Kotlin files, module boundaries, TV source validation, dependency resolution and function-size checks passed. Official wrapper checksum and the matching Playwright image manifest were verified.

The selected code and manual adaptations received a final static review with no further blocking findings. Physical-device playback, Android 9/10 archive parsing, production deployment and a new signed package were not performed.

Exact command logs and remote cleanup/recovery records are in `artifacts-local/branch-integration/20261014/` (local and ignored). Remote CI results, when available, are recorded separately rather than being implied by local checks.
