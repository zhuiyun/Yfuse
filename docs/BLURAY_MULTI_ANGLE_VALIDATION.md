# Blu-ray multi-angle validation

Yfuse treats authored Blu-ray angles as optical-disc navigation state, not as separate media versions.
The UI-facing index is zero based internally and rendered as `视角 1`, `视角 2`, and so on.

## Native path

The custom native build patches the private `yfusebd://` libbluray session with a dedicated
`nativeSelectAngle` JNI call. Selection is accepted only when the active `BLURAY_TITLE_INFO` reports
that angle, then calls libbluray `bd_seamless_angle_change`, refreshes title information, and pushes the
new angle state through the same asynchronous navigation callback used by title/chapter/menu updates.

The BDMV `yfusebdmv://` path receives an independent JNI namespace and reuses the same session logic.
Its Kotlin VFS proxy decorates the BDMV HDMV session with angle count/current angle, while ordinary
mpv remains responsible for title/chapter playback. This avoids pretending mpv's open-time
`bluray-angle` option is a stable runtime selector.

`YfuseMpvCapabilities.MULTI_ANGLE` and the `multi-angle=true` native manifest entry are created only by
the pinned native build patch. The AAR verifier requires both the ISO and BDMV `nativeSelectAngle`
symbols before the artifact can be installed/promoted.

## Required physical validation

A release may claim multi-angle only after at least one legally available authored disc passes all of
the following on the exact shipping AAR:

- angle count/current angle match the disc's authored playlist;
- switching from angle 1 -> N -> 1 does not restart the title, lose audio/subtitle intent, or move the
  playback clock outside the normal seamless-transition tolerance;
- `BD_EVENT_ANGLE` and the explicit JNI selection converge on the same selected angle;
- chapter/title switching refreshes the available angle count and cannot leave a stale out-of-range
  selection;
- HDMV menu-driven angle changes and direct YCore angle changes remain consistent;
- rapid repeated input is serialized by the libbluray session mutex and causes no native crash,
  deadlock, or JNI-global-reference leak;
- remote raw ISO, local seekable ISO and BDMV VFS each either switch successfully or report the
  capability unavailable without breaking main-feature playback;
- A/V sync after a seamless angle change remains inside the normal YCore release gate.

Until this corpus passes, `MULTI_ANGLE=true` means the exact native **implementation exists in the
artifact**, not that every Android decoder/device has been certified for every seamless-angle title.
