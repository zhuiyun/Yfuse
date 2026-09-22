# Android–HarmonyOS parity contract

Android is the observable product baseline. HarmonyOS uses Cangjie and ArkUI, but a platform
implementation is complete only when the matching screen, state, action, persistence behavior and
error path in this directory pass.

The contract deliberately separates three concepts:

1. `screen-catalog.yaml` lists every user-visible surface and required state.
2. `feature-matrix.json` lists functional parity and capability-gated claims.
3. `design-tokens.json` is the platform-neutral source consumed by Android and Harmony generators.
4. `implementation-coverage.json` maps every Harmony target to source files and SDK/hardware gates.

Platform differences are allowed only below these contracts. In particular, HarmonyOS system
playback may use AVPlayer while Android uses Media3, and both remain compliant when `YPlayer` state,
controls, reporting, fallbacks and diagnostics are equivalent.

Dolby Vision, encoded-audio passthrough and optical-disc claims require runtime evidence. Source
metadata, bundled source code or an engine label never counts as proof of active output.

Run `python3 scripts/verify-harmony-port.py` before every HarmonyOS handoff. It validates the
contracts and scaffold, then compiles and executes the portable YCore coordinator tests on the host.
