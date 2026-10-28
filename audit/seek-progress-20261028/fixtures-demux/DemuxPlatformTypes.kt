package com.yfuse.core2.android

import android.content.Context
import android.media.MediaFormat
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials
import java.nio.ByteBuffer
import java.util.UUID

/** Type-only boundaries for unexecuted Android/JNI implementations in host queue tests. */
internal data class YAndroidMediaSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val credentials: YTransportCredentials? = null,
    val bitrateBitsPerSecond: Long = 0L,
    val cacheIdentity: YCacheIdentity? = null,
    val cacheMaximumBytes: Long = 0L,
    val originBoundHeaderNames: Set<String> = emptySet(),
)

internal data class YExtractorSample(
    val trackIndex: Int,
    val data: ByteBuffer,
    val presentationTimeUs: Long,
    val flags: Int,
    val cryptoInfo: Any? = null,
    val queueGeneration: Long = -1L,
)

internal interface YPlatformExtractorSource {
    val name: String
    fun open(source: YAndroidMediaSource)
    val trackCount: Int
    fun trackFormat(index: Int): MediaFormat
    fun findFirstTrack(mimePrefix: String): Int?
    fun readSourcePrefix(maximumBytes: Int): ByteArray?
    fun drmInitializationData(schemeUuid: UUID): ByteArray?
    fun setMediaBitRateBitsPerSecond(value: Long)
    fun updatePlaybackWindow(window: YTransportPlaybackWindow) = Unit
    fun transportQoeSnapshot(): YTransportPrefetchQoeSnapshot?
    fun blockedForegroundReadMs(): Long
    fun liveTransportThroughput(): Long? = null
    fun cancelPendingRead() = Unit
    fun abortPendingReads() = cancelPendingRead()
    fun selectTrack(index: Int)
    fun unselectTrack(index: Int)
    fun seekTo(positionUs: Long)
    fun readSample(target: ByteBuffer): YExtractorSample?
    fun advance(): Boolean
    fun flush()
    fun release()
}

/** Constructors are linked but never used: the production queue receives a test source. */
internal class AndroidMediaExtractorDemuxNode(
    context: Context,
    onBlockingReadStateChanged: ((Boolean) -> Unit)? = null,
) : YPlatformExtractorSource {
    override val name: String get() = error("Real Android MediaExtractor unavailable in host tests")
    override fun open(source: YAndroidMediaSource): Unit = error(name)
    override val trackCount: Int get() = error(name)
    override fun trackFormat(index: Int): MediaFormat = error(name)
    override fun findFirstTrack(mimePrefix: String): Int? = error(name)
    override fun readSourcePrefix(maximumBytes: Int): ByteArray? = error(name)
    override fun drmInitializationData(schemeUuid: UUID): ByteArray? = error(name)
    override fun setMediaBitRateBitsPerSecond(value: Long): Unit = error(name)
    override fun transportQoeSnapshot(): YTransportPrefetchQoeSnapshot? = error(name)
    override fun blockedForegroundReadMs(): Long = error(name)
    override fun selectTrack(index: Int): Unit = error(name)
    override fun unselectTrack(index: Int): Unit = error(name)
    override fun seekTo(positionUs: Long): Unit = error(name)
    override fun readSample(target: ByteBuffer): YExtractorSample? = error(name)
    override fun advance(): Boolean = error(name)
    override fun flush(): Unit = error(name)
    override fun release(): Unit = error(name)
}

internal abstract class AndroidFfmpegDemuxer : YDemuxer {
    fun open(source: YDemuxSource, budget: AndroidProbeBudget?): YDemuxOpenResult =
        error("Native FFmpeg unavailable in host tests")
}
