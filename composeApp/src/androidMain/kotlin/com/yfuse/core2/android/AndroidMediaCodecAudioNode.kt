package com.yfuse.core2.android

import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import com.yfuse.core2.graph.YAudioDecodeNode
import java.nio.ByteBuffer

internal sealed interface YAudioCodecOutputResult {
    data object TryAgain : YAudioCodecOutputResult

    data class Buffer(
        val index: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val offset: Int,
        val size: Int,
    ) : YAudioCodecOutputResult {
        val endOfStream: Boolean get() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    data class FormatChanged(
        val format: MediaFormat,
    ) : YAudioCodecOutputResult
}

/** Compressed audio decoder used by the baseline NativeDirect graph. */
internal class AndroidMediaCodecAudioNode(
    private val createDecoder: (String) -> MediaCodec = MediaCodec::createDecoderByType,
) : YAudioDecodeNode {
    override val name: String = "MediaCodecAudio"

    private var codec: MediaCodec? = null
    private var started = false

    val decoderName: String? get() = codec?.name

    fun configure(
        format: MediaFormat,
        mediaCrypto: MediaCrypto? = null,
    ) {
        release()
        val mime =
            format.getString(MediaFormat.KEY_MIME)
                ?: error("Audio MediaFormat is missing ${MediaFormat.KEY_MIME}")
        val working = format.copyForCodecAttempt().also(MediaFormat::applyAudioMaxInputSizeFloor)
        val decoder = createDecoder(mime)
        try {
            decoder.configure(working, null, mediaCrypto, 0)
            decoder.start()
            codec = decoder
            started = true
        } catch (throwable: Throwable) {
            runCatching { decoder.release() }
            throw throwable
        }
    }

    fun queueAccessUnit(
        data: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int = 0,
        cryptoInfo: YExtractorCryptoInfo? = null,
    ): YCodecQueueResult {
        val decoder = requireStartedCodec()
        val inputIndex = decoder.dequeueInputBuffer(0L)
        if (inputIndex < 0) return YCodecQueueResult.TryAgain
        val input = decoder.getInputBuffer(inputIndex) ?: error("Audio codec input buffer unavailable")
        input.clear()
        val sample = data.duplicate()
        val size = sample.remaining()
        require(size <= input.remaining()) {
            "Encoded audio sample ($size bytes) exceeds MediaCodec input buffer (${input.remaining()} bytes)"
        }
        input.put(sample)
        val encrypted = flags and EXTRACTOR_SAMPLE_ENCRYPTED != 0
        require(encrypted == (cryptoInfo != null)) { "Encrypted audio sample metadata is inconsistent" }
        if (cryptoInfo == null) {
            decoder.queueInputBuffer(
                inputIndex,
                0,
                size,
                presentationTimeUs,
                flags.toAudioCodecInputFlags(),
            )
        } else {
            decoder.queueSecureInputBuffer(
                inputIndex,
                0,
                cryptoInfo.toMediaCodecCryptoInfo(),
                presentationTimeUs,
                flags.toAudioCodecInputFlags(),
            )
        }
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

    fun dequeueOutput(): YAudioCodecOutputResult {
        val decoder = requireStartedCodec()
        val info = MediaCodec.BufferInfo()
        return when (val outputIndex = decoder.dequeueOutputBuffer(info, 0L)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> YAudioCodecOutputResult.TryAgain
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                YAudioCodecOutputResult.FormatChanged(decoder.outputFormat)
            else -> {
                if (outputIndex < 0) return YAudioCodecOutputResult.TryAgain
                YAudioCodecOutputResult.Buffer(
                    index = outputIndex,
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                    offset = info.offset,
                    size = info.size,
                )
            }
        }
    }

    fun outputData(output: YAudioCodecOutputResult.Buffer): ByteBuffer {
        val source =
            requireStartedCodec().getOutputBuffer(output.index)
                ?: error("Audio codec output buffer unavailable")
        val end = output.offset + output.size
        require(output.offset >= 0 && end <= source.capacity()) {
            "Invalid audio codec output range ${output.offset}..$end for capacity ${source.capacity()}"
        }
        return source
            .duplicate()
            .apply {
                position(output.offset)
                limit(end)
            }.slice()
    }

    fun releaseOutput(output: YAudioCodecOutputResult.Buffer) {
        requireStartedCodec().releaseOutputBuffer(output.index, false)
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
            check(started) { "MediaCodec audio node has not been configured" }
        }
}

private fun Int.toAudioCodecInputFlags(): Int = if (this and EXTRACTOR_SAMPLE_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

private const val EXTRACTOR_SAMPLE_SYNC = 1
private const val EXTRACTOR_SAMPLE_ENCRYPTED = 2
