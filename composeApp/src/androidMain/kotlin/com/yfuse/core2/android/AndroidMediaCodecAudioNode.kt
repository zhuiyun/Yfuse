package com.yfuse.core2.android

import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.graph.YAudioDecodeNode
import kotlinx.coroutines.CancellationException
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
    private val formatReplay = CodecOutputFormatReplay<YAudioCodecOutputResult.Buffer>()
    private var configuration: AudioCodecConfiguration? = null
    private var inputQueuedSinceFlush = false
    private var formatSynthesisLogged = false

    val decoderName: String? get() = codec?.name

    fun configure(
        format: MediaFormat,
        mediaCrypto: MediaCrypto? = null,
        trackFormat: YAudioTrackFormat? = null,
    ) {
        release()
        configureCodec(format, mediaCrypto, trackFormat)
        configuration = AudioCodecConfiguration(format, mediaCrypto, trackFormat)
    }

    private fun configureCodec(
        format: MediaFormat,
        mediaCrypto: MediaCrypto?,
        trackFormat: YAudioTrackFormat?,
    ) {
        val mime =
            format
                .getString(MediaFormat.KEY_MIME)
                ?.normalizedAudioMimeType()
                ?.takeIf(String::isNotBlank)
                ?: error("Audio MediaFormat is missing ${MediaFormat.KEY_MIME}")
        val working =
            format
                .copyForCodecAttempt()
                .also { codecFormat ->
                    // MediaExtractor may append parameters (notably DTS:X `profile=p2`).
                    // MediaCodec requires the canonical MIME in the factory call and KEY_MIME.
                    codecFormat.setString(MediaFormat.KEY_MIME, mime)
                    if (codecFormat.positiveInteger(MediaFormat.KEY_CHANNEL_COUNT) == null) {
                        trackFormat?.channelCount?.takeIf { it > 0 }?.let { channelCount ->
                            codecFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        }
                    }
                    if (codecFormat.positiveInteger(MediaFormat.KEY_SAMPLE_RATE) == null) {
                        trackFormat?.sampleRate?.takeIf { it > 0 }?.let { sampleRate ->
                            codecFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        }
                    }
                    codecFormat.applyAudioMaxInputSizeFloor()
                }
        val decoder = createDecoder(mime)
        try {
            decoder.configure(working, null, mediaCrypto, 0)
            decoder.start()
            codec = decoder
            started = true
            formatReplay.reset()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
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
        // A dequeued input buffer already belongs to this generation; only flush() returns it.
        inputQueuedSinceFlush = true
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
        inputQueuedSinceFlush = true
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
        formatReplay.takeHeld()?.let { return it }
        val info = MediaCodec.BufferInfo()
        return when (val outputIndex = decoder.dequeueOutputBuffer(info, 0L)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> YAudioCodecOutputResult.TryAgain
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                formatReplay.formatReported()
                YAudioCodecOutputResult.FormatChanged(decoder.outputFormat)
            }
            else -> {
                if (outputIndex < 0) return YAudioCodecOutputResult.TryAgain
                val buffer =
                    YAudioCodecOutputResult.Buffer(
                        index = outputIndex,
                        presentationTimeUs = info.presentationTimeUs,
                        flags = info.flags,
                        offset = info.offset,
                        size = info.size,
                    )
                val carriesSamples = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                if (carriesSamples && formatReplay.holdIfFormatMissing(buffer)) {
                    val format = decoder.outputFormat
                    logSynthesizedFormat(format)
                    YAudioCodecOutputResult.FormatChanged(format)
                } else {
                    buffer
                }
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

    /**
     * Discards decoder input for a seek.
     *
     * A decoder fed nothing since it was configured or flushed has nothing to discard, and flushing
     * it could only lose an output-format report it has not delivered yet, so it is left alone. One
     * that was fed but has not reported its PCM format is rebuilt instead of flushed: flush() drops
     * that pending report and the codec never repeats it. NativeDirect's startup seek did exactly
     * that, and the format the replay then had to synthesize failed one tablet's startup (incident E).
     */
    override fun flush() {
        formatReplay.flush()
        if (!started || !inputQueuedSinceFlush) return
        inputQueuedSinceFlush = false
        val previous = configuration
        if (!formatReplay.formatKnown && previous != null) {
            AppLog.info(
                category = "player.core2",
                event = "audio_decoder_rebuilt_for_seek",
                message = "Rebuilt the audio decoder for a seek before it reported its PCM format",
                attributes = mapOf("decoder" to decoderName.orEmpty()),
            )
            releaseCodec()
            configureCodec(previous.format, previous.mediaCrypto, previous.trackFormat)
            return
        }
        codec?.flush()
    }

    override fun release() {
        releaseCodec()
        configuration = null
    }

    private fun releaseCodec() {
        val decoder = codec
        codec = null
        val wasStarted = started
        started = false
        inputQueuedSinceFlush = false
        formatReplay.reset()
        if (decoder != null) {
            if (wasStarted) runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private fun logSynthesizedFormat(format: MediaFormat) {
        if (formatSynthesisLogged) return
        formatSynthesisLogged = true
        AppLog.warning(
            category = "player.core2",
            event = "audio_output_format_synthesized",
            message = "Audio decoder produced PCM without reporting its format; replayed its current format",
            attributes =
                mapOf(
                    "decoder" to decoderName.orEmpty(),
                    "sampleRate" to format.integerOrEmpty(MediaFormat.KEY_SAMPLE_RATE),
                    "channelCount" to format.integerOrEmpty(MediaFormat.KEY_CHANNEL_COUNT),
                    "channelMask" to format.integerOrEmpty(MediaFormat.KEY_CHANNEL_MASK),
                    "pcmEncoding" to format.integerOrEmpty(MediaFormat.KEY_PCM_ENCODING),
                ),
        )
    }

    private fun requireStartedCodec(): MediaCodec =
        checkNotNull(codec).also {
            check(started) { "MediaCodec audio node has not been configured" }
        }
}

private fun MediaFormat.positiveInteger(key: String): Int? =
    runCatching { getInteger(key) }.getOrNull()?.takeIf { it > 0 }

private fun MediaFormat.integerOrEmpty(key: String): String =
    if (containsKey(key)) runCatching { getInteger(key).toString() }.getOrDefault("") else ""

/** Everything [AndroidMediaCodecAudioNode.configure] needs to build the same decoder again. */
private class AudioCodecConfiguration(
    val format: MediaFormat,
    val mediaCrypto: MediaCrypto?,
    val trackFormat: YAudioTrackFormat?,
)

private fun Int.toAudioCodecInputFlags(): Int =
    if (this and EXTRACTOR_SAMPLE_SYNC !=
        0
    ) {
        MediaCodec.BUFFER_FLAG_KEY_FRAME
    } else {
        0
    }

private const val EXTRACTOR_SAMPLE_SYNC = 1
private const val EXTRACTOR_SAMPLE_ENCRYPTED = 2

/**
 * Replays an output format that MediaCodec dropped.
 *
 * A codec can report INFO_OUTPUT_FORMAT_CHANGED before the caller dequeues it; `flush()` then
 * discards that pending report and the codec never repeats it, because its format did not change.
 * A startup seek flushes exactly then, and the next PCM buffer used to fail playback with "output
 * before format". The first sample-carrying buffer without a reported format is therefore held
 * behind a synthesized format change taken from the codec's current output format.
 */
internal class CodecOutputFormatReplay<B : Any> {
    private var formatReported = false
    private var held: B? = null

    /** True once a format was delivered, reported by the codec or synthesized here. */
    val formatKnown: Boolean get() = formatReported

    fun reset() {
        formatReported = false
        held = null
    }

    /** Held buffers belong to the pre-flush generation; a delivered format still applies. */
    fun flush() {
        held = null
    }

    fun formatReported() {
        formatReported = true
    }

    fun takeHeld(): B? = held.also { held = null }

    /** True when [buffer] must be returned after a synthesized format change. */
    fun holdIfFormatMissing(buffer: B): Boolean {
        if (formatReported) return false
        formatReported = true
        held = buffer
        return true
    }
}
