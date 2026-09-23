# Local and remote integration — 2026-09-23

## Scope

- Repository: `zhuiyun/Yfuse`, integration branch: `master`.
- Fetched all configured remote branches with pruning before integration.
- Starting commit: `aaf946dfe`; recovery branch: `codex/pre-merge-local-cloud-20260923`.
- All remote branch tips were already ancestors of the starting commit.
- The only unmerged local branch was `fix/review-20260917` at `de2021326` (five commits).
- Checked 134 local/remote branch references: every tip is covered by the two merge parents.

## Conflict resolution

Resolved 15 conflicted paths. Reused and checked the existing integration resolutions in
`D:/tmp/yfuse-merge`, including its unstaged Plex `compareSources(...).single()` adaptation.
That worktree and its pending merge were left intact.

The review branch contributes account/server adapter cleanup, cancellation handling,
playback-sync persistence improvements, shared UI components, accessibility/design tokens,
TV presentation changes and regression tests.

Kept the current `PlayerRoot` and `PlaybackEngineSlot` implementation: the older review
branch's five extracted player files and corresponding `PlayerEngineHandoverTest` target
an earlier orchestration design. Their original contents remain on the review branch.
Current source switching, serialized engine retirement, session recovery and frame-rate
overlay code are retained. The proxy constructor includes both the current admission/
header-timeout controls and the review branch's injectable bandwidth sampling clock.

Preserved current dependency versions and refreshed affected lockfile configurations
for Ktor, Kotlin serialization and Bouncy Castle using `resolve-merge-locks.init.gradle`.
The starting branch declared serialization 1.11.0 while locks still required 1.8.0;
the first network-enabled test run reproduced this incompatibility.
No APK was built;
`version.properties` and `release-notes.txt` remain unchanged at 1.0.80 / 242.

## Other local state

- The primary checkout was clean before merging.
- `D:/tmp/yfuse-merge` changes are included in this integration without modifying that checkout.
- Other active branch worktrees were clean.
- A historical detached 0.2.05 validation worktree contains only a modified version file;
  it is preserved rather than replacing current release metadata.
- All 17 historical stashes are retained. The newest stash's static/signed playback URL
  fixes and regression tests are already present in the current code, with subsequent
  authentication fixes. Historical backup stashes were not blindly reapplied.

## Validation

- `git diff --cached --check`: passed.
- Index has no unresolved merge entries.
- Conflict-marker scan of added/modified source/config files: passed.
- Checked removed accent compatibility symbols for remaining references: none.
- Tests requested: `:phoneShared:testAndroidHostTest`, `:tvShared:testAndroidHostTest`,
  `:watchTogetherProtocol:jvmTest`, `:watchTogetherServer:test`.
- Wrapper download timed out. Located the already-installed Gradle 9.7.1 and used the
  project's existing Windows Unix-domain-socket workaround to start it successfully.
- Offline validation initially failed before compilation because Kotlin 2.4.20 `gradle96`
  plugin artifacts were missing. The configured HTTPS proxy enabled dependency downloads.
- After resolving dependency locks, production phone code compiled. Host-test compilation
  exposed two newly added tests still calling the review branch's changed
  `awaitQueuedSamples` helper without its fake demuxer argument; adapted both callers.
- All four suites passed: phone 2,966; TV 63; protocol 8; server 209.
  Total: 3,246 tests, zero failures/errors/skips.
- Full validation and lock refresh succeeded: `tests-fixed.log`.
  Normal locked-mode verification also passed (`BUILD SUCCESSFUL`): `final-validation.log`.
  Reproduction entry point: `validate.ps1`.

No push or publication was performed.
