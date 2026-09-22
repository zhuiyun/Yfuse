# Dolby Vision Profile 7 enhancement-layer validation

Yfuse separates **source-layer metadata** from **output evidence**. This prevents the player from
turning a Dolby Vision badge, an RPU flag or `ElPresentFlag=1` into a claim that the Full Enhancement
Layer was actually composed.

## Source facts

Emby/Jellyfin may expose:

- Dolby Vision profile;
- RPU present;
- enhancement layer present;
- base layer present;
- base-layer compatibility id.

For Profile 7, EL presence means only that the encoded source is a dual-layer validation candidate.
It does not identify MEL versus FEL by itself, and it does not prove that the selected decoder/render
pipeline consumed the EL.

## Output evidence states

`evaluateDolbyVisionP7Output()` returns one of:

- `NotApplicable` — not a Profile 7 + EL source;
- `NotMeasured` — P7 + EL exists but no trustworthy output measurement exists;
- `BaseLayerOnly` — a trace proves only BL frames reached output;
- `BaseLayerWithRpu` — a trace proves BL plus RPU handling but not EL composition;
- `EnhancementLayerComposed` — an output/physical trace explicitly proves the source EL participated in
  final composition.

Only `EnhancementLayerComposed` sets `canClaimFel=true`.

A decoder name, display Dolby Vision capability, HDR badge, successful playback, server
`ElPresentFlag`, or RPU presence is never sufficient by itself. Contradictory source metadata such as
EL present with BL explicitly absent cannot be promoted to a FEL claim by a downstream boolean.

## Release gate

The UI and release notes must continue to use neutral labels such as `Dolby Vision P7 · 双层` until a
shipping device/backend combination has explicit enhancement-composition evidence. If Yfuse later
implements a BL+EL reconstruction path, the validation artifact must identify the backend/build and
record only redacted capability/evidence facts; media URLs, titles, server ids, account ids and tokens
are excluded.

A fallback that keeps BL+RPU while discarding the enhancement layer can still be a useful compatible
Dolby Vision route, but it is explicitly **not** FEL preservation and must never flip `canClaimFel`.
