package com.yfuse.core2.android

import android.view.Surface
import com.yfuse.core2.api.YVideoOutput

/** Opaque Android adapter for the common [YVideoOutput] contract. */
internal class AndroidSurfaceVideoOutput(
    val surface: Surface,
    /** True only when the owning SurfaceView was marked secure before this Surface was attached. */
    val protectedContent: Boolean = false,
    /** The host observes AndroidVideoOutputRenewal and can create a fresh buffer queue. */
    val renewable: Boolean = false,
) : YVideoOutput
