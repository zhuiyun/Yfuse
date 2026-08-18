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

internal sealed interface YCodecDrainResult {
    data object TryAgain : YCodecDrainResult

    data class FrameRendered(
        val presentationTimeUs: Long,
        val flags: Int,
    ) : YCodecDrainResult

    data class OutputFormatChanged(
        val format: MediaFormat,
    ) : YCodecDrainResult
}

/**
 * Core2's first native video primitive: compressed access units enter MediaCodec and decoded output
 * goes straight to a Surface. No decoded YUV frame is copied back through the CPU or Compose.
 */
internal class AndroidMediaCodecVideoNode(
    private val createDecoder: (String) -> MediaCodec = MediaCodec::createDecoderByType,
) : YVideoDecodeNode {
    override val name: String = "MediaCodec"

    private var codec: MediaCodec? = null
    private var started = false

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
            flags,
        )
        return YCodecQueueResult.Queued
    }

    /** Non-blocking drain; successful buffers are released directly to the configured Surface. */
    fun drainOutput(render: Boolean = true): YCodecDrainResult {
        val decoder = requireStartedCodec()
        val info = MediaCodec.BufferInfo()
        return when (val outputIndex = decoder.dequeueOutputBuffer(info, 0L)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> YCodecDrainResult.TryAgain
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                YCodecDrainResult.OutputFormatChanged(decoder.outputFormat)
            else -> {
                if (outputIndex < 0) return YCodecDrainResult.TryAgain
                decoder.releaseOutputBuffer(outputIndex, render)
                YCodecDrainResult.FrameRendered(
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                )
            }
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
