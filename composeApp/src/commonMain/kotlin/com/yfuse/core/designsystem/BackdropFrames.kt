package com.yfuse.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Draw observers must subscribe even before the first captured frame is available. */
internal class BackdropFrames {
    private var revision by mutableIntStateOf(0)

    // Do not read snapshot state in recorded(): that would make the source redraw itself.
    private var records = 0

    val available: Boolean get() = revision != 0

    fun recorded() {
        records = if (records == Int.MAX_VALUE) 1 else records + 1
        revision = records
    }
}
