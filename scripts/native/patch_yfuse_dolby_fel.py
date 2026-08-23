#!/usr/bin/env python3
"""Adds evidence markers to mpv's gpu-next Dolby Vision render path.

The marker is emitted only after libplacebo successfully renders the frame. RPU evidence requires
an actual rendered pl_frame carrying Dolby Vision metadata; FEL evidence additionally requires the
Profile 7 enhancement layer to be attached to that rendered frame. Exact anchors deliberately make
source drift fail the native build instead of silently publishing an AAR with a false capability.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_yfuse_dolby_fel.py <vo_gpu_next.c>")

path = Path(sys.argv[1])
text = path.read_text()

if "YFUSE_DOVI_FEL_COMPOSED" in text:
    raise SystemExit("Dolby Vision evidence patch is already present")

state_old = """    bool frame_pending;\n    bool paused;\n\n    pl_options pars;"""
state_new = """    bool frame_pending;\n    bool paused;\n\n    // Runtime proof exported to the Android wrapper. These are one-shot per video reconfigure.\n    bool yfuse_dovi_rpu_reported;\n    bool yfuse_dovi_fel_reported;\n\n    pl_options pars;"""
if state_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: private-state anchor missing")
text = text.replace(state_old, state_new, 1)

reconfig_old = """static int reconfig(struct vo *vo, struct mp_image_params *params)\n{\n    struct priv *p = vo->priv;"""
reconfig_new = """static int reconfig(struct vo *vo, struct mp_image_params *params)\n{\n    struct priv *p = vo->priv;\n    p->yfuse_dovi_rpu_reported = false;\n    p->yfuse_dovi_fel_reported = false;"""
if reconfig_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: reconfig anchor missing")
text = text.replace(reconfig_old, reconfig_new, 1)

render_old = """    if (!render_ok) {\n        MP_ERR(vo, \"Failed rendering frame!\\n\");\n        goto done;\n    }\n"""
render_new = render_old + """

    // A source badge or decoder name is not output evidence. Inspect the exact frame that
    // libplacebo just rendered successfully, then publish a one-shot marker for Android.
    const struct pl_frame *yfuse_frame = pl_frame_mix_current(&mix);
    if (yfuse_frame && yfuse_frame->repr.dovi) {
        if (!p->yfuse_dovi_rpu_reported) {
            MP_INFO(vo, "YFUSE_DOVI_RPU_RENDERED\\n");
            p->yfuse_dovi_rpu_reported = true;
        }
#if PL_API_VER >= 367
        // libplacebo's enhancement_layer is the P7 EL input consumed by the successful render.
        // Do not advertise FEL on older libplacebo APIs where this field is unavailable.
        if (yfuse_frame->enhancement_layer && !p->yfuse_dovi_fel_reported) {
            MP_INFO(vo, "YFUSE_DOVI_FEL_COMPOSED\\n");
            p->yfuse_dovi_fel_reported = true;
        }
#endif
    }
"""
if render_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: successful-render anchor missing")
text = text.replace(render_old, render_new, 1)

# Make sure this source is new enough for the FEL pipeline before writing the result.
required = (
    "frame->enhancement_layer = &fp->el_frame",
    "pl_frame_mix_current",
    "PL_API_VER >= 367",
)
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("mpv source lacks required FEL support: " + ", ".join(missing))

path.write_text(text)
