# HarmonyOS Cangjie port status

## Completed in source

- Android parity contracts, screen/state catalog, design tokens and capability gates.
- Native HarmonyOS module scaffold with Cangjie UI entry, five retained root-tab stacks and all
  catalogued secondary routes.
- Emby, Jellyfin and Plex request/response codecs, guarded HTTP transport, multi-server registry
  invariants, Quick Connect/PIN flows and aggregate search coordination.
- Encrypted Asset Store token bridge with no plaintext fallback.
- System `Video` player surface and controls, unified `YPlayer` contract, route policy, diagnostics,
  subtitle/output/disc decisions and stable Cangjie-to-C FFI.
- Portable YCore engine coordinator with capability/priority routing and state-preserving handover.
- Contracts for downloads, encrypted sync, DLNA, watch together, danmaku, diagnostics and updates.
- SDK-independent contract tests, fixture checks, Cangjie structural checks, YCore behavior tests and
  shared-library ABI export checks.

## Release gates that source code cannot satisfy

- A matching DevEco Cangjie SDK, `cjc`, `cjpm`, Hvigor and production signing configuration are not
  present in this environment, so a signed HAP cannot be compiled here.
- The public Cangjie ArkUI wrapper exposes `Video` but documents `XComponent` and custom render nodes
  as unsupported. NativeWindow rendering, FFmpeg/libass composition and ISO/BDMV therefore stay
  disabled and fail closed.
- Dolby Vision, encoded-audio passthrough, PiP/background controls, Asset Store durability, LAN
  discovery/casting and the media/durability matrix require signed builds and physical-device or
  receiver evidence.
- The Harmony branch is cut from Android `master` at `45d39439`. Harmony additions do not alter
  Android build inputs; Android production and quality workflows passed before this baseline was
  recorded.

Run `python3 scripts/verify-harmony-port.py` for repeatable source validation and
`python3 scripts/harmony-release-gate.py` before any release build. The latter intentionally exits
with code 2 until every SDK adapter, signing and hardware evidence gate is verified.
