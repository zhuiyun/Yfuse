# Yfuse HarmonyOS release checklist

A Release HAP is accepted only when every item below has captured evidence. An unchecked capability
must stay disabled in the build and absent from user-facing “active output” claims.

## Build

- [ ] DevEco Studio/HarmonyOS API 20 Cangjie SDK installed and `DEVECO_CANGJIE_HOME` resolved.
- [ ] `cjpm.toml` compiles for arm64-v8a and x86_64 without warnings promoted by the release profile.
- [ ] Native libraries contain no absolute build paths and ship only the selected ABIs.
- [ ] Release signing config uses production-owned credentials outside the repository.
- [ ] Obfuscation, symbol archive and open-source notices are generated.

## Security and data

- [ ] Asset Store add/query/update/remove passes after reboot and device lock/unlock.
- [ ] Metadata database contains secret references only; tokens never appear in Preferences/logs.
- [ ] HTTP server setup requires explicit local-network cleartext acknowledgement.
- [ ] Cross-origin redirects never receive Emby/Jellyfin/Plex authorization headers.
- [ ] Account sync ciphertext, nonce uniqueness and AAD swap rejection tests pass.

## UI parity

- [ ] Phone/tablet light/dark, landscape, large-font and reduced-motion golden captures pass.
- [ ] Geometry delta is at most 2 vp and fixed color delta-E is at most 3.
- [ ] All loading/content/empty/offline/error states in `parity/screen-catalog.yaml` are exercised.
- [ ] TalkBack/focus order, labels, hit targets and contrast have no critical failures.
- [ ] Artwork sampling modifies only the page surface exposed by the bottom dissolve.

## Media and system integration

- [ ] Every case in `parity/media-validation-matrix.json` records route and expected outcome.
- [ ] Authentication/DRM errors do not switch engines; eligible decoder/render errors do.
- [ ] Seek, speed, track selection, pause intent and position survive engine handover.
- [ ] Background playback, lock-screen controls, audio focus, interruption and PiP pass.
- [ ] Download pause/resume, ETag change, checksum, storage exhaustion and logout pass.
- [ ] DLNA discovery/control and credential proxy revocation pass on a physical LAN.

## Evidence-gated output

- [ ] NativeWindow host is verified before enabling `YFUSE_ENABLE_HARMONY_NATIVE_RENDERER`.
- [ ] FFmpeg/libass/libbluray inputs are pinned, audited and built for each enabled ABI.
- [ ] Dolby Vision evidence includes decoder, renderer and active display output.
- [ ] Encoded audio evidence includes codec and active receiver/sink format.
- [ ] ISO/BDMV evidence includes local/remote random access, playlist, chapter and angle tests.

## Durability and handoff

- [ ] Eight-hour playback, 500 seeks, 100 handovers and 100 background transitions pass.
- [ ] Fatal crash and ANR counts are zero; A/V sync and dropped-frame thresholds pass.
- [ ] `python3 scripts/verify-harmony-port.py` passes from a clean checkout.
- [ ] Release HAP, native symbols, validation report and dependency notices are archived together.
