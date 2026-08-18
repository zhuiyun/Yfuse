package com.yfuse.core2.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.yfuse.core2.graph.YVideoDecodeNode
import java.nio.ByteBuffer

/** Result of a non-blocking compressed-sample enqueue. */
internal enum class YCodecQueueResult {
    Queued,
    TryAgain,
}

internal sealed interface YCodecOutputResult {
    data object TryAgain : YCodecOutputResult

    data class Buffer(
        val index: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val size: Int,
    ) : YCodecOutputResult {
        val endOfStream: Boolean get() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    data class FormatChanged(
        val format: MediaFormat,
    ) : YCodecOutputResult
}

/**
 * Core2's native video primitive: compressed access units enter MediaCodec and decoded output goes
 * straight to a Surface. No decoded YUV frame is copied back through the CPU or Compose.
 *
 * Dequeue and release are deliberately separate. The future audio-master clock can decide exactly
 * when a frame should be presented before `releaseOutputBuffer(index, renderTimeNs)` hands it to
 * the Surface/OEM HDR pipeline.
 */
internal class AndroidMediaCodecVideoNode(
    private val createDecoder: (String) -> MediaCodec = MediaCodec::createDecoderByType,
) : YVideoDecodeNode {
    override val name: String = "MediaCodec"

    private var codec: MediaCodec? = null
    private var started = false

    val decoderName: String? get() = codec?.name

    fun configure(
        format: MediaFormat,
        surface: Surface,
    ) {
        release()
        val mime =
            format.getString(MediaFormat.KEY_MIME)
                ?: error("Video MediaFormat is missing ${MediaFormat.KEY_MIME}")
        val decoder = createDecoder(mime)
        try {
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            started = true
        } catch (throwable: Throwable) {
            runCatching { decoder.release() }
            throw throwable
        }
    }

    /** Changes the target without decoding through a texture or CPU buffer. */
    fun setOutputSurface(surface: Surface) {
        requireStartedCodec().setOutputSurface(surface)
    }

    /**
     * Queues one complete encoded access unit. The compressed sample is copied only into the codec
     * input buffer; decoded output remains on the Surface path.
     */
    fun queueAccessUnit(
        data: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int = 0,
    ): YCodecQueueResult {
        val decoder = requireStartedCodec()
        val inputIndex = decoder.dequeueInputBuffer(0L)
        if (inputIndex < 0) return YCodecQueueResult.TryAgain

        val input = decoder.getInputBuffer(inputIndex) ?: error("MediaCodec input buffer unavailable")
        input.clear()
        val sample = data.duplicate()
        val size = sample.remaining()
        require(size <= input.remaining()) {
            "Encoded access unit ($size bytes) exceeds MediaCodec input buffer (${input.remaining()} bytes)"
        }
        input.put(sample)
        decoder.queueInputBuffer(
            inputIndex,
            0,
            size,
            presentationTimeUs,
            flags.toCodecInputFlags(),
        )
        return YCodecQueueResult.Queued
    }

    fun queueEndOfStream(presentationTimeUs: Long): YCodecQueueResult {
        val decoder = requireStartedCodec()
        val inputIndex = decoder.dequeueInputBuffer(0L)
        if (inputIndex < 0) return YCodecQueueResult.TryAgain
        decoder.queueInputBuffer(
            inputIndex,
            0,
            0,
            presentationTimeUs.coerceAtLeast(0L),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
        return YCodecQueueResult.Queued
    }

    /** Non-blocking output dequeue. The caller owns the returned buffer until releaseOutput(). */
    fun dequeueOutput(): YCodecOutputResult {
        val decoder = requireStartedCodec()
        val info = MediaCodec.BufferInfo()
        return when (val outputIndex = decoder.dequeueOutputBuffer(info, 0L)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> YCodecOutputResult.TryAgain
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                YCodecOutputResult.FormatChanged(decoder.outputFormat)
            else -> {
                if (outputIndex < 0) return YCodecOutputResult.TryAgain
                YCodecOutputResult.Buffer(
                    index = outputIndex,
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                    size = info.size,
                )
            }
        }
    }

    /**
     * Releases a decoded frame directly to the Surface. A non-null [renderTimeNs] uses Android's
     * timed release API; null renders immediately. `render=false` discards without touching a GPU.
     */
    fun releaseOutput(
        output: YCodecOutputResult.Buffer,
        render: Boolean,
        renderTimeNs: Long? = null,
    ) {
        val decoder = requireStartedCodec()
        if (!render) {
            decoder.releaseOutputBuffer(output.index, false)
        } else if (renderTimeNs != null) {
            decoder.releaseOutputBuffer(output.index, renderTimeNs)
        } else {
            decoder.releaseOutputBuffer(output.index, true)
        }
    }

    override fun flush() {
        if (started) codec?.flush()
    }

    override fun release() {
        val decoder = codec
        codec = null
        val wasStarted = started
        started = false
        if (decoder != null) {
            if (wasStarted) runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private fun requireStartedCodec(): MediaCodec =
        checkNotNull(codec).also {
            check(started) { "MediaCodec video node has not been configured" }
        }
}

/** MediaExtractor's SYNC bit matches MediaCodec's key-frame bit; encrypted samples are not queued here. */
private fun Int.toCodecInputFlags(): Int =
    if (this and MediaExtractorFlags.ENCRYPTED != 0) {
        error("Encrypted samples require a MediaCrypto queue path")
    } else {
        if (this and MediaExtractorFlags.SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
    }

/** Kept local so the video node does not depend on MediaExtractor at runtime. */
private object MediaExtractorFlags {
    const val SYNC = 1
    const val ENCRYPTED = 2
}
