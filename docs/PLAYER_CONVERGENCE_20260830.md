# Player convergence — 2026-08-30

This milestone closes product controls around the existing YCore planner and Exo/MPV/MDK
executors. It does not introduce another decoder or rendering kernel.

## Implemented contract

| Area | Behaviour | Boundary |
|---|---|---|
| A/V sync | Per-series audio delay and subtitle offset are restored and bounded | Unsupported locked engines report unavailable; Auto may select existing MPV |
| Subtitles | Size, HDR brightness, position, text colour, background and outline | Core2 overlay, Exo caption view and MPV implement the shared appearance contract |
| Audio | Volume boost and night voice dynamic-range compression | MPV execution path; Auto may hand over, native-only/locked unsupported paths stay explicit |
| Current video | Engine choice in the player is session-only | It no longer writes the global engine preference |
| Fallback | Error overlay and playback settings can open an Android external player | Authenticated URL is never logged; Android chooser owns the handoff |
| Seek | Touch/remote/Cast seeks use a 120 ms latest-wins reducer | HUD remains immediate; duplicate network/receiver commands are dropped |
| Cast | Native queue load/next/previous, active track selection, progress/volume state, output receipt and disconnect handoff | Receiver publishing and physical-device results remain external evidence |

## Server compatibility

- Emby remains the fully supported API implementation.
- Jellyfin is detected from `/System/Info/Public` and persisted as a distinct provider while using
  the shared MediaBrowser-compatible API and playback profile. This avoids silently labelling a
  Jellyfin session as Emby and preserves the provider across sync/backup.
- Player, external-player and Cast queue contracts consume already-resolved HTTP media URLs and are
  provider-neutral, so a Plex adapter can feed them without changing a playback engine.
- Plex authentication, library browsing, metadata parsing and progress reporting are **not**
  implemented by this milestone. External playback of an already-resolved Plex URL is supported;
  full Plex server support requires a separate provider adapter and conformance corpus.

## Required external verification

1. GitHub Actions Android compile/unit/quality gates (local Gradle distribution was unavailable in
   the execution environment).
2. Google Cast Console must show receiver `E9107559` as published with at least one sender app.
3. Run the physical Chromecast matrix in `castReceiver/README.md`.
4. Run Emby and Jellyfin playback/reporting smoke tests; run Plex only after the provider adapter is
   implemented rather than treating external-player fallback as full compatibility.
