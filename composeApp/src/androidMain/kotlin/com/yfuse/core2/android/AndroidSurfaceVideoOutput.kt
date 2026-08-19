package com.yfuse.core2.android

import android.view.Surface
import com.yfuse.core2.api.YVideoOutput

/** Opaque Android adapter for the common [YVideoOutput] contract. */
internal class AndroidSurfaceVideoOutput(
    val surface: Surface,
) : YVideoOutput
