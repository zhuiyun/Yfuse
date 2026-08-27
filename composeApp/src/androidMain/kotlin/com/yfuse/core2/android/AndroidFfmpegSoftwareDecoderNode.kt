package com.yfuse.core2.android

import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YTrackId
import java.nio.ByteBuffer

internal sealed interface YSoftwareVideoDecodeResult {
    data object TryAgain : YSoftwareVideoDecodeResult

    data object Ended : YSoftwareVideoDecodeResult

    data class Frame(
        val data: ByteBuffer,
        val presentationTimeUs: Long,
        val width: Int,
        val height: Int,
        val strideBytes: Int,
    ) : YSoftwareVideoDecodeResult
}

internal sealed interface YSoftwareAudioDecodeResult {
    data object TryAgain : YSoftwareAudioDecodeResult

    data object Ended : YSoftwareAudioDecodeResult

    data class Frame(
        val data: ByteBuffer,
        val presentationTimeUs: Long,
        val channelCount: Int,
        val sampleRate: Int,
        val sampleCount: Int,
    ) : YSoftwareAudioDecodeResult
}

/** Bounded reusable buffers around the optional FFmpeg software decoder JNI extension. */
internal class AndroidFfmpegSoftwareDecoderNode(
    private val demuxer: AndroidFfmpegDemuxer,
) {
    private var videoTrackId: YTrackId? = null
    private var audioTrackId: YTrackId? = null
    private var videoBuffer = ByteBuffer.allocateDirect(INITIAL_VIDEO_FRAME_BYTES)
    private var audioBuffer = ByteBuffer.allocateDirect(INITIAL_AUDIO_FRAME_BYTES)

    val available: Boolean get() = demuxer.softwareDecodeAvailable

    fun configureVideo(trackId: YTrackId) {
        demuxer.configureSoftwareDecoder(trackId)
        videoTrackId = trackId
    }

    fun configureAudio(trackId: YTrackId) {
        demuxer.configureSoftwareDecoder(trackId)
        audioTrackId = trackId
    }

    fun queueVideo(sample: YCompressedSample?): Boolean = demuxer.sendSoftwarePacket(requireNotNull(videoTrackId), sample)

    fun queueAudio(sample: YCompressedSample?): Boolean = demuxer.sendSoftwarePacket(requireNotNull(audioTrackId), sample)

    fun receiveVideo(): YSoftwareVideoDecodeResult {
        val trackId = requireNotNull(videoTrackId)
        repeat(MAX_GROW_RETRIES) {
            val result = demuxer.receiveSoftwareVideoFrame(trackId, videoBuffer)
            require(result.size >= SOFTWARE_FRAME_RESULT_FIELDS) { "Invalid FFmpeg software video result" }
            when (result[SOFTWARE_FRAME_STATUS]) {
                SOFTWARE_FRAME_AGAIN -> return YSoftwareVideoDecodeResult.TryAgain
                SOFTWARE_FRAME_EOF -> return YSoftwareVideoDecodeResult.Ended
                SOFTWARE_FRAME_GROW -> {
                    val required = result[SOFTWARE_FRAME_SIZE]
                    require(required in 1..MAX_VIDEO_FRAME_BYTES.toLong()) {
                        "FFmpeg software video frame exceeds the Kotlin safety limit"
                    }
                    videoBuffer = ByteBuffer.allocateDirect(required.toInt())
                }
                SOFTWARE_FRAME_DATA -> {
                    val size = result[SOFTWARE_FRAME_SIZE].validSize(videoBuffer, MAX_VIDEO_FRAME_BYTES)
                    val width = result[SOFTWARE_FRAME_FIRST].toInt()
                    val height = result[SOFTWARE_FRAME_SECOND].toInt()
                    val stride = result[SOFTWARE_FRAME_THIRD].toInt()
                    require(width > 0 && height > 0 && stride >= width * BYTES_PER_BGRA_PIXEL)
                    return YSoftwareVideoDecodeResult.Frame(
                        data = videoBuffer.frameSlice(size),
                        presentationTimeUs = result[SOFTWARE_FRAME_PTS].timestampOrZero(),
                        width = width,
                        height = height,
                        strideBytes = stride,
                    )
                }
                else -> error("Unknown FFmpeg software video status")
            }
        }
        error("FFmpeg software video buffer did not converge")
    }

    fun receiveAudio(): YSoftwareAudioDecodeResult {
        val trackId = requireNotNull(audioTrackId)
        repeat(MAX_GROW_RETRIES) {
            val result = demuxer.receiveSoftwareAudioFrame(trackId, audioBuffer)
            require(result.size >= SOFTWARE_FRAME_RESULT_FIELDS) { "Invalid FFmpeg software audio result" }
            when (result[SOFTWARE_FRAME_STATUS]) {
                SOFTWARE_FRAME_AGAIN -> return YSoftwareAudioDecodeResult.TryAgain
                SOFTWARE_FRAME_EOF -> return YSoftwareAudioDecodeResult.Ended
                SOFTWARE_FRAME_GROW -> {
                    val required = result[SOFTWARE_FRAME_SIZE]
                    require(required in 1..MAX_AUDIO_FRAME_BYTES.toLong()) {
                        "FFmpeg software audio frame exceeds the Kotlin safety limit"
                    }
                    audioBuffer = ByteBuffer.allocateDirect(required.toInt())
                }
                SOFTWARE_FRAME_DATA -> {
                    val size = result[SOFTWARE_FRAME_SIZE].validSize(audioBuffer, MAX_AUDIO_FRAME_BYTES)
                    val channels = result[SOFTWARE_FRAME_FIRST].toInt()
                    val sampleRate = result[SOFTWARE_FRAME_SECOND].toInt()
                    val samples = result[SOFTWARE_FRAME_THIRD].toInt()
                    require(channels in 1..32 && sampleRate in 1..768_000 && samples > 0)
                    require(size == channels * samples * Short.SIZE_BYTES)
                    return YSoftwareAudioDecodeResult.Frame(
                        data = audioBuffer.frameSlice(size),
                        presentationTimeUs = result[SOFTWARE_FRAME_PTS].timestampOrZero(),
                        channelCount = channels,
                        sampleRate = sampleRate,
                        sampleCount = samples,
                    )
                }
                else -> error("Unknown FFmpeg software audio status")
            }
        }
        error("FFmpeg software audio buffer did not converge")
    }

    fun flush() {
        videoTrackId?.let(demuxer::flushSoftwareDecoder)
        audioTrackId?.let(demuxer::flushSoftwareDecoder)
    }

    fun release() {
        videoTrackId = null
        audioTrackId = null
        videoBuffer.clear()
        audioBuffer.clear()
    }
}

private fun Long.validSize(
    buffer: ByteBuffer,
    maximum: Int,
): Int = toInt().also { require(it in 1..minOf(buffer.capacity(), maximum)) }

private fun ByteBuffer.frameSlice(size: Int): ByteBuffer =
    duplicate()
        .apply {
            position(0)
            limit(size)
        }.slice()

private fun Long.timestampOrZero(): Long = takeUnless { it == Long.MIN_VALUE }?.coerceAtLeast(0L) ?: 0L

private const val SOFTWARE_FRAME_RESULT_FIELDS = 6
private const val SOFTWARE_FRAME_STATUS = 0
private const val SOFTWARE_FRAME_SIZE = 1
private const val SOFTWARE_FRAME_PTS = 2
private const val SOFTWARE_FRAME_FIRST = 3
private const val SOFTWARE_FRAME_SECOND = 4
private const val SOFTWARE_FRAME_THIRD = 5
private const val SOFTWARE_FRAME_AGAIN = 0L
private const val SOFTWARE_FRAME_DATA = 1L
private const val SOFTWARE_FRAME_EOF = 2L
private const val SOFTWARE_FRAME_GROW = -1L
private const val INITIAL_VIDEO_FRAME_BYTES = 4 * 1024 * 1024
private const val INITIAL_AUDIO_FRAME_BYTES = 256 * 1024
private const val MAX_VIDEO_FRAME_BYTES = 128 * 1024 * 1024
private const val MAX_AUDIO_FRAME_BYTES = 8 * 1024 * 1024
private const val BYTES_PER_BGRA_PIXEL = 4
private const val MAX_GROW_RETRIES = 2
