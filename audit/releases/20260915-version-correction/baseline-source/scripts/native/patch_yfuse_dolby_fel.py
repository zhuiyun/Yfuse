#!/usr/bin/env python3
"""Adds runtime RPU/FEL evidence to mpv's gpu-next Dolby Vision render path.

Evidence is recorded only after libplacebo successfully renders the exact frame. RPU evidence
requires rendered Dolby Vision metadata; FEL evidence additionally requires the P7 enhancement
layer on that frame. A tiny exported atomic snapshot lets the Android wrapper read this fact
without parsing human-readable mpv logs.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_yfuse_dolby_fel.py <vo_gpu_next.c>")

path = Path(sys.argv[1])
text = path.read_text()

if "YFUSE_DOVI_FEL_COMPOSED" in text:
    raise SystemExit("Dolby Vision evidence patch is already present")

include_old = "#include <sys/stat.h>\n#include <time.h>\n"
include_new = "#include <sys/stat.h>\n#include <time.h>\n#include <stdatomic.h>\n"
if include_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: standard include anchor missing")
text = text.replace(include_old, include_new, 1)

state_old = """struct osd_entry {
    pl_tex tex;
"""
state_new = """// Process-local evidence for the single fullscreen mpv renderer. A generation changes on each
// video reconfigure, clearing all bits before the next frame can become visible.
enum {
    YFUSE_DOVI_EVIDENCE_RPU = 1u << 0,
    YFUSE_DOVI_EVIDENCE_FEL = 1u << 1,
};
static atomic_uint_fast64_t yfuse_dovi_generation = 0;
static atomic_uint_fast32_t yfuse_dovi_evidence = 0;

__attribute__((visibility("default")))
uint64_t yfuse_mpv_dolby_generation(void)
{
    return atomic_load_explicit(&yfuse_dovi_generation, memory_order_acquire);
}

__attribute__((visibility("default")))
uint32_t yfuse_mpv_dolby_evidence(void)
{
    return (uint32_t)atomic_load_explicit(&yfuse_dovi_evidence, memory_order_acquire);
}

struct osd_entry {
    pl_tex tex;
"""
if state_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: global-state insertion anchor missing")
text = text.replace(state_old, state_new, 1)

priv_old = """    bool frame_pending;
    bool paused;

    pl_options pars;"""
priv_new = """    bool frame_pending;
    bool paused;

    // One-shot logs are useful in diagnostics; policy reads the atomic snapshot above.
    bool yfuse_dovi_rpu_reported;
    bool yfuse_dovi_fel_reported;

    pl_options pars;"""
if priv_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: private-state anchor missing")
text = text.replace(priv_old, priv_new, 1)

reconfig_old = """static int reconfig(struct vo *vo, struct mp_image_params *params)
{
    struct priv *p = vo->priv;"""
reconfig_new = """static int reconfig(struct vo *vo, struct mp_image_params *params)
{
    struct priv *p = vo->priv;
    p->yfuse_dovi_rpu_reported = false;
    p->yfuse_dovi_fel_reported = false;
    atomic_store_explicit(&yfuse_dovi_evidence, 0, memory_order_release);
    atomic_fetch_add_explicit(&yfuse_dovi_generation, 1, memory_order_release);"""
if reconfig_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: reconfig anchor missing")
text = text.replace(reconfig_old, reconfig_new, 1)

render_old = """    if (!render_ok) {
        MP_ERR(vo, \"Failed rendering frame!\\n\");
        goto done;
    }
"""
render_new = render_old + """

    // Source metadata and decoder names are not output evidence. Inspect the exact frame that
    // libplacebo just rendered successfully and publish only facts consumed by that render.
    const struct pl_frame *yfuse_frame = pl_frame_mix_current(&mix);
    if (yfuse_frame && yfuse_frame->repr.dovi) {
        atomic_fetch_or_explicit(&yfuse_dovi_evidence, YFUSE_DOVI_EVIDENCE_RPU,
                                 memory_order_release);
        if (!p->yfuse_dovi_rpu_reported) {
            MP_INFO(vo, "YFUSE_DOVI_RPU_RENDERED\\n");
            p->yfuse_dovi_rpu_reported = true;
        }
#if PL_API_VER >= 367
        // mpv's P7 path feeds the paired EL through pl_frame.enhancement_layer. A successful
        // render with that input is the evidence required by YCore's FEL claim gate.
        if (yfuse_frame->enhancement_layer) {
            atomic_fetch_or_explicit(&yfuse_dovi_evidence, YFUSE_DOVI_EVIDENCE_FEL,
                                     memory_order_release);
            if (!p->yfuse_dovi_fel_reported) {
                MP_INFO(vo, "YFUSE_DOVI_FEL_COMPOSED\\n");
                p->yfuse_dovi_fel_reported = true;
            }
        }
#endif
    }
"""
if render_old not in text:
    raise SystemExit("unexpected vo_gpu_next.c: successful-render anchor missing")
text = text.replace(render_old, render_new, 1)

required = (
    "frame->enhancement_layer = &fp->el_frame",
    "pl_frame_mix_current",
    "PL_API_VER >= 367",
    "yfuse_mpv_dolby_generation",
    "yfuse_mpv_dolby_evidence",
)
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("mpv source lacks required FEL support: " + ", ".join(missing))

path.write_text(text)
