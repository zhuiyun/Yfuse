# HarmonyOS Cangjie port status

## Source wiring completed; HAP verification blocked (2026-09-10)

The root home, library, server and search screens now connect to a retained media session,
repository and persistent server registry. The Emby/Jellyfin path includes login, server selection,
paged library/search, detail, authenticated same-origin playback, resume and progress checkpoints.
Existing per-tab navigation stacks are retained. Legacy secondary screens still contain placeholders.

This is source wiring, not a verified running Harmony application. The matching DevEco Cangjie
SDK and host stdx libraries are absent. No HAP has been built or run. The affected coverage entries
use `sourceWired`; none has been promoted to `implemented`. Hardware testing is excluded from
this task at the user's request.

## What exists in source

- Android parity contracts, screen and state catalog, design tokens and capability gates.
- A HarmonyOS module scaffold: `module.json5`, Hvigor with Cangjie build support, `cjpm.toml`, an
  ability, an ability stage and a Cangjie UI entry.
- Emby, Jellyfin and Plex request builders and response decoders over a guarded HTTP transport that
  refuses to send server credentials across an origin change. Seven endpoints are covered; Android
  splits the same surface across twelve services.
- `ServerRegistry` multi-server invariants with secret-reference indirection and rollback, now
  backed by a `NativeStringPreferenceStore` and the NativePreferences C API. The C++ bridge passes
  syntax checking against the installed OpenHarmony native SDK; device durability remains unverified.
- An encrypted Asset Store token bridge over `ysecure_huks.cpp` with no plaintext fallback. This is a
  real FFI implementation and its exported symbols are checked by the verification script.
- Pure decision functions for playback route, dynamic range, audio output, subtitle route and disc
  preflight. Each is unit-shaped and correct in isolation; nothing calls any of them.
- Quick Connect request builders, aggregate-search fan-out with a generation guard, and the artwork
  bottom-band color algorithm. All three are complete and unreferenced.
- A `Video`-based player screen wired to real repository playback URLs, resume and progress
  callbacks. The nine advanced panels are not evidence that their platform features work.
- Bounded local playback progress storage and server checkpoints. Server failures are reported;
  durable offline retry and cross-account synchronization remain incomplete.
- Interfaces, with no implementations, for LAN discovery, downloads, DLNA, background playback and
  picture-in-picture, encrypted sync, diagnostics export, updates and disc navigation.
- The portable YCore coordinator in `ycore-native`, which compiles under `-Werror` and passes its
  host tests. No Harmony playback surface reaches it and `NativeYPlayer` is never constructed.
- SDK-independent contract checks, fixture checks, Cangjie structural checks, YCore behavior tests
  and shared-library ABI export checks.

## First compiler verification (2026-09-08)

A standalone Cangjie compiler is now installed on the development machine: `cjc` 1.0.5 (cjnative),
targeting `x86_64-w64-mingw32`. It cannot build a HAP, but it type-checks every package that does
not import `ohos.*`, which is 41 of the module's 48 files. This is the first time any Cangjie source
here has been through a compiler.

The sources were migrated from the pinned 0.53.18 dialect to 1.0.5. Four breaking changes were
found and fixed:

- `ArrayList.append(x)` became `add(x)`, 45 call sites. The `DownloadSink.append` contract is this
  project's own three-argument interface method and was deliberately left alone.
- `ArrayList.remove(i)` became `remove(at: i)`. Key-based removals on `HashMap` and `SecretStore`
  keep their old form.
- `type` became a reserved word. `SkipMarker.type` is renamed `kind` rather than back-ticked at
  five call sites.
- Enums no longer derive equality implicitly. `TransportDecision`, `OfflineState`, `TrackType`,
  `PlaybackPhase` and `ProviderKind` are compared with `==`/`!=` and now carry `@Derive[Equatable]`.

Two further changes were needed once the first errors cleared:

- `const` initializers no longer accept a constructor call, so the thirteen capability bits are
  written `1u64 << n` instead of `UInt64(1) << n`.
- `PlaybackEvidence.toJson` moved out of the player package and became
  `support.playbackEvidenceJson`. Serialization belongs with the exporter that already owns it, and
  keeping it here made the entire player package depend on stdx, which put every playback decision
  beyond the reach of a host test.

Current result after removing media discovery: 8 of 12 platform-independent packages compile —
`data`, `design`, `watch`, `player`, `app`, `cast`, `offline` and `sync`. The remaining four
(`network`, `storage`, `support`, `provider`) are blocked by missing `stdx.encoding.json` and
`stdx.encoding.url`. Imports now use the stdx namespace; `CANGJIE_STDX_PATH` can supply a matching
host library. Missing dependencies prevent a complete type check of these packages.

## Executable evidence

`scripts/verify-cangjie-host.py` compiles those eight packages and runs 33 unit tests against them.
It is wired into `verify-harmony-port.py` and skips itself with a notice when no host compiler is
installed, so a machine with only Android tooling still passes.

The tests cover the decisions a release claim rests on: playback route selection and its refusal to
pick the native engine without a surface, subtitle routing failing closed for ASS and PGS, HDR and
Dolby Vision fallback order, encoded-audio passthrough, the output badge never reading "active"
without full evidence, optical-disc preflight, skip and sleep timers, dual-subtitle offset clamping,
watch-together drift correction and sequence rejection, danmaku scheduling and filtering, per-tab
navigation stacks, and search generation guarding with history de-duplication.

Writing them found no defects in the logic. One test was wrong instead: the danmaku scheduler only
emits comments inside a 500 ms window behind the playhead, so a forward seek does not dump the
backlog. That is deliberate, and the test now documents it.

## Release gates that source code cannot satisfy

- The installed compiler targets Windows. The HarmonyOS toolchain the module needs —
  `aarch64-linux-ohos` and `x86_64-linux-ohos`, the musl sysroot, and the `ohos.*` ArkUI modules
  the seven UI and platform files import — comes with the DevEco Cangjie plugin, which is not
  installed. DevEco Studio 6.1.0.830 is present but carries only the ArkTS stack. Without it no
  HAP can be produced and the seven `ohos.*` files stay unverified.
- `stdx` is required before the remaining four packages can be type-checked.
- Production signing configuration is not present, so a signed HAP cannot be produced here.
- The public Cangjie ArkUI wrapper exposes `Video` but documents `XComponent` and custom render nodes
  as unsupported. NativeWindow rendering, FFmpeg/libass composition and ISO/BDMV therefore stay
  disabled and fail closed. This caps the product: ASS and PGS subtitles, optical disc and the native
  YCore route cannot ship at all under the current public API.
- Dolby Vision, encoded-audio passthrough, PiP/background controls, Asset Store durability, LAN
  discovery/casting and the media/durability matrix require signed builds and physical-device or
  receiver evidence. None has been collected for the newly wired playback path.

## Baseline drift

The parity contracts name Android `45d39439` (2026-08-24) as the source of truth. `master` has moved
779 commits past it. The catalog and feature matrix therefore describe an Android product that is
several weeks old, and re-basing them is a prerequisite for treating parity failures as real.

`harmonyApp` is merged into `master` as of `4db30a08`; `feature/harmony-cangjie` still exists but is
no longer where this code lives. See `BRANCH_AUDIT.md`.

Run `python3 scripts/verify-harmony-port.py` for repeatable source validation and
`python3 scripts/harmony-release-gate.py` before any release build. The latter intentionally exits
with code 2 until every SDK adapter, signing and hardware evidence gate is verified.
