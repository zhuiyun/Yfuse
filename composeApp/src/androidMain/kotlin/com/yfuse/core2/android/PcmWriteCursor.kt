package com.yfuse.core2.android

import java.nio.ByteBuffer

/** Keeps a decoder buffer's original byte offset through partial writes and audio-server rebuilds. */
internal class PcmWriteCursor {
    private var source: ByteBuffer? = null
    private var startPosition = 0
    private var nextPosition = 0
    private var limit = 0
    private var timestampUs = 0L

    fun positionUs(
        data: ByteBuffer,
        presentationTimeUs: Long,
        frameBytes: Int,
        sampleRate: Int,
    ): Long {
        require(frameBytes > 0 && sampleRate > 0)
        if (source !== data ||
            timestampUs != presentationTimeUs ||
            limit != data.limit() ||
            nextPosition != data.position()
        ) {
            source = data
            startPosition = data.position()
            limit = data.limit()
            timestampUs = presentationTimeUs
        }
        nextPosition = data.position()
        return presentationTimeUs + (data.position() - startPosition).toLong() / frameBytes * 1_000_000L / sampleRate
    }

    fun consumed(data: ByteBuffer) {
        nextPosition = data.position()
        if (!data.hasRemaining()) source = null
    }
}
