package com.yfuse.core2.android

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer

internal data class AndroidTunnelEncodedSample(
    val trackIndex: Int,
    val data: ByteBuffer,
    val presentationTimeUs: Long,
    val codecFlags: Int,
    val encrypted: Boolean,
)

/**
 * MediaExtractor adapter with explicit peek/advance ownership for Tunnel.
 *
 * The current compressed access unit is cached until MediaCodec accepts it, so codec backpressure
 * never advances the extractor early. No decoded frame or PCM data passes through this class.
 */
internal class AndroidTunnelPlatformDemuxer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var extractor: MediaExtractor? = null
    private var cachedSample: AndroidTunnelEncodedSample? = null
    private var selectedTracks: Set<Int> = emptySet()

    fun open(source: YAndroidMediaSource) {
        release()
        val next = MediaExtractor()
        try {
            next.setDataSource(appContext, Uri.parse(source.uri), source.headers)
        } catch (failure: Throwable) {
            next.release()
            throw failure
        }
        extractor = next
    }

    val trackCount: Int get() = requireExtractor().trackCount

    fun trackFormat(index: Int): MediaFormat = requireExtractor().getTrackFormat(index)

    fun findFirstTrack(mimePrefix: String): Int? =
        (0 until trackCount).firstOrNull { index ->
            trackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith(mimePrefix, ignoreCase = true) == true
        }

    fun selectTracks(indices: Set<Int>) {
        val active = requireExtractor()
        selectedTracks.forEach { index -> runCatching { active.unselectTrack(index) } }
        indices.forEach { index ->
            require(index in 0 until active.trackCount) { "Tunnel track index is outside extractor" }
            active.selectTrack(index)
        }
        selectedTracks = indices.toSet()
        cachedSample = null
    }

    /** Returns the current compressed sample without advancing the extractor. */
    fun peekSample(): AndroidTunnelEncodedSample? {
        cachedSample?.let { return it }
        val active = requireExtractor()
        val trackIndex = active.sampleTrackIndex
        if (trackIndex < 0) return null
        if (trackIndex !in selectedTracks) {
            // MediaExtractor should normally expose only selected tracks; defensively skip any
            // unexpected stream rather than queueing it into the wrong decoder.
            active.advance()
            return peekSample()
        }
        val size = sampleSize(active)
        val buffer = ByteBuffer.allocateDirect(size.coerceAtLeast(MIN_SAMPLE_BUFFER_BYTES))
        val read = active.readSampleData(buffer, 0)
        if (read < 0) return null
        buffer.position(0)
        buffer.limit(read)
        val sampleFlags = active.sampleFlags
        return AndroidTunnelEncodedSample(
            trackIndex = trackIndex,
            data = buffer,
            presentationTimeUs = active.sampleTime.coerceAtLeast(0L),
            codecFlags =
                if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                },
            encrypted = sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0,
        ).also { cachedSample = it }
    }

    /** Call exactly once after the cached sample has been accepted by its decoder. */
    fun advance(): Boolean {
        cachedSample = null
        return requireExtractor().advance()
    }

    fun seekTo(positionUs: Long) {
        cachedSample = null
        requireExtractor().seekTo(
            positionUs.coerceAtLeast(0L),
            MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
        )
    }

    fun release() {
        cachedSample = null
        selectedTracks = emptySet()
        extractor?.release()
        extractor = null
    }

    private fun sampleSize(extractor: MediaExtractor): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val size = extractor.sampleSize
            if (size > 0L) {
                require(size <= MAX_SAMPLE_BUFFER_BYTES.toLong()) {
                    "Tunnel encoded sample exceeds safety limit"
                }
                return size.toInt()
            }
        }
        return DEFAULT_SAMPLE_BUFFER_BYTES
    }

    private fun requireExtractor(): MediaExtractor =
        checkNotNull(extractor) { "Tunnel MediaExtractor is not opened" }
}

private const val MIN_SAMPLE_BUFFER_BYTES = 64 * 1024
private const val DEFAULT_SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024
private const val MAX_SAMPLE_BUFFER_BYTES = 64 * 1024 * 1024
