package com.yfuse.core2.android

import java.nio.ByteBuffer

internal data class PendingAudioBuffer<T>(
    val output: T,
    val data: ByteBuffer,
)

/** Actor-owned codec buffer: retain through partial writes, release exactly once. */
internal class AndroidPendingAudioOutput<T>(
    private val release: (T) -> Unit,
) {
    var current: PendingAudioBuffer<T>? = null
        private set

    fun hold(
        output: T,
        data: ByteBuffer,
    ): PendingAudioBuffer<T> {
        check(current == null) { "Unconsumed audio output must not be overwritten" }
        return PendingAudioBuffer(output, data).also { current = it }
    }

    fun complete(expected: PendingAudioBuffer<T>) {
        check(current === expected) { "Audio output belongs to an earlier decode operation" }
        check(!expected.data.hasRemaining()) { "Audio output is not fully consumed" }
        current = null
        release(expected.output)
    }

    fun discard() {
        val pending = current ?: return
        current = null
        runCatching { release(pending.output) }
    }

    /** Used only once the decoder has already been flushed/reset by its owner. */
    fun clearAfterDecoderReset() {
        current = null
    }
}
