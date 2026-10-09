package com.yfuse.core2.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

/** Negotiates the real sink format, retaining one conversion buffer until its partial write finishes. */
internal class SoftwarePcmOutput(
    private val allowPacked24: Boolean,
) {
    private data class Input(
        val rate: Int,
        val channels: Int,
        val format: AndroidPcmSampleFormat,
    )

    private var input: Input? = null
    private var output = AndroidPcmSampleFormat.Signed16
    private val storage = AndroidPlaybackStagingBuffer(1, MAX_SOFTWARE_PCM_BYTES)
    private val dither = Random(0x59434f52)

    fun matches(frame: YSoftwareAudioDecodeResult.Frame): Boolean = input == frame.shape()

    fun configure(
        frame: YSoftwareAudioDecodeResult.Frame,
        open: (AndroidPcmSampleFormat) -> Unit,
    ) {
        var failure: RuntimeException? = null
        for (candidate in softwarePcmCandidates(frame.sampleFormat, allowPacked24)) {
            try {
                open(candidate)
                input = frame.shape()
                output = candidate
                return
            } catch (error: RuntimeException) {
                if (error !is IllegalArgumentException &&
                    error !is IllegalStateException &&
                    error !is UnsupportedOperationException
                ) {
                    throw error
                }
                failure = error
            }
        }
        throw checkNotNull(failure)
    }

    fun convert(frame: YSoftwareAudioDecodeResult.Frame): YSoftwareAudioDecodeResult.Frame {
        check(matches(frame))
        if (frame.sampleFormat == output) return frame
        require(frame.sampleFormat == AndroidPcmSampleFormat.Float32)
        val bytes = frame.sampleCount.toLong() * frame.channelCount * output.bytes
        require(bytes in 1..MAX_SOFTWARE_PCM_BYTES.toLong())
        val target = storage.grow(bytes.toInt()).duplicate().order(ByteOrder.LITTLE_ENDIAN)
        target.clear().limit(bytes.toInt())
        convertFloatPcm(frame.data, target, output, dither)
        target.flip()
        return frame.copy(data = target, sampleFormat = output)
    }

    fun release() {
        input = null
        storage.close()
    }

    private fun YSoftwareAudioDecodeResult.Frame.shape() = Input(sampleRate, channelCount, sampleFormat)
}

internal fun softwarePcmCandidates(
    source: AndroidPcmSampleFormat,
    packed24: Boolean,
): List<AndroidPcmSampleFormat> =
    if (source == AndroidPcmSampleFormat.Float32) {
        buildList {
            add(AndroidPcmSampleFormat.Float32)
            if (packed24) add(AndroidPcmSampleFormat.Signed24Packed)
            add(AndroidPcmSampleFormat.Signed16)
        }
    } else {
        listOf(source)
    }

/** TPDF dither is only used on an actual integer fallback, never on the float output path. */
internal fun convertFloatPcm(
    source: ByteBuffer,
    target: ByteBuffer,
    format: AndroidPcmSampleFormat,
    dither: Random,
) {
    require(format == AndroidPcmSampleFormat.Signed16 || format == AndroidPcmSampleFormat.Signed24Packed)
    val input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    target.order(ByteOrder.LITTLE_ENDIAN)
    val scale = if (format == AndroidPcmSampleFormat.Signed16) 32_768 else 8_388_608
    while (input.hasRemaining()) {
        val sample = input.getFloat().let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
        val value =
            (sample.toDouble() * scale + dither.nextDouble() - dither.nextDouble())
                .roundToInt()
                .coerceIn(-scale, scale - 1)
        if (format == AndroidPcmSampleFormat.Signed16) {
            target.putShort(value.toShort())
        } else {
            target.put(value.toByte()).put((value shr 8).toByte()).put((value shr 16).toByte())
        }
    }
}

private const val MAX_SOFTWARE_PCM_BYTES = 8 * 1024 * 1024
