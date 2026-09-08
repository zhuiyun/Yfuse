package com.yfuse.core2.android

import android.content.Context
import android.media.MediaFormat

/** Tunnel shares the cancellable transport and single-owner compressed queue used by Direct. */
internal class AndroidTunnelPlatformDemuxer(
    private val readAhead: AndroidMediaExtractorReadAheadNode,
) {
    constructor(context: Context) : this(AndroidMediaExtractorReadAheadNode(context))

    private var cachedSample: YExtractorSample? = null

    fun open(source: YAndroidMediaSource) {
        cachedSample = null
        readAhead.open(source)
    }

    val trackCount: Int get() = readAhead.trackCount

    fun trackFormat(index: Int): MediaFormat = readAhead.trackFormat(index)

    fun findFirstTrack(mimePrefix: String): Int? = readAhead.findFirstTrack(mimePrefix)

    fun selectTracks(indices: Set<Int>) = readAhead.selectTracks(indices, startReadAhead = false)

    fun configureBufferPlan(
        targetAheadUs: Long,
        maximumBytes: Long,
    ) = readAhead.configureBufferPlan(targetAheadUs, maximumBytes)

    fun startReadAhead() = readAhead.startReadAhead()

    fun pauseReadAhead() = readAhead.pauseReadAhead()

    fun snapshot(): YExtractorReadAheadSnapshot = readAhead.snapshot()

    fun updatePlaybackWindow(window: YTransportPlaybackWindow) = readAhead.updatePlaybackWindow(window)

    /** TryAgain keeps the same sample; Empty is distinct from EOF and never blocks the codec pump. */
    fun peekSample(): YQueuedExtractorResult =
        cachedSample?.let { YQueuedExtractorResult.Sample(it) } ?: readAhead.pollSample().also { result ->
            if (result is YQueuedExtractorResult.Sample) cachedSample = result.value
        }

    fun advance() {
        cachedSample = null
    }

    fun seekTo(positionUs: Long) {
        cachedSample = null
        readAhead.seekTo(positionUs)
    }

    fun cancelPendingRead() = readAhead.cancelPendingRead()

    fun release() {
        cachedSample = null
        readAhead.release()
    }

    fun close() = readAhead.close()
}
