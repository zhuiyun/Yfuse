package com.yfuse.core2.android

/** Thread-safe interruption is separate from permanent cancellation; resumption is owner-only. */
internal interface AndroidDemuxReadControl {
    fun interruptRead(generation: Long)

    fun resumeRead(generation: Long): Boolean

    fun cancelPendingRead()
}
