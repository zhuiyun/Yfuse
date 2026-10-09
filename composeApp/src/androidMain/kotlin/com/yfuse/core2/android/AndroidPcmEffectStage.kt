package com.yfuse.core2.android

import com.yfuse.core2.audio.YAudioEffectProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** PCM sample layouts AudioTrack plays, by the size of one sample. */
internal enum class AndroidPcmSampleFormat(
    val bytes: Int,
) {
    Unsigned8(1),
    Signed16(2),
    Signed24Packed(3),
    Signed32(4),
    Float32(4),
}

/**
 * Runs [processor] over the PCM a renderer writes without touching the decoder's buffer: codec
 * output buffers are read-only, and one the sink takes only part of is offered again later. Each
 * buffer is processed once into a staging copy, the copy is what AudioTrack gets, and the original
 * advances by whatever the sink took, so a re-offered remainder continues from the copy instead of
 * going through the effect a second time.
 */
internal class AndroidPcmEffectStage(
    val processor: YAudioEffectProcessor,
    val format: AndroidPcmSampleFormat,
) {
    private val frameBytes = format.bytes * processor.channelCount
    private var source: ByteBuffer? = null
    private var staged: ByteBuffer? = null
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var samples = FloatArray(0)

    /** The processed bytes to write in place of [data]'s remaining ones. */
    fun input(data: ByteBuffer): ByteBuffer {
        val copy = staged
        if (copy != null && source === data && copy.remaining() == data.remaining()) return copy
        return process(data).also {
            source = data
            staged = it
        }
    }

    /** The sink took [count] bytes of the copy [input] returned; [data] advances by as many. */
    fun consumed(
        data: ByteBuffer,
        count: Int,
    ) {
        if (count > 0) data.position(data.position() + count)
        if (!data.hasRemaining()) discardPending()
    }

    /** Forgets a part-written copy, whose decoder buffer is gone, and keeps the processor's state. */
    fun discardPending() {
        source = null
        staged = null
    }

    /** A seek or flush: nothing pending, and the processor's detectors start over. */
    fun reset() {
        discardPending()
        processor.reset()
    }

    private fun process(data: ByteBuffer): ByteBuffer {
        val length = data.remaining()
        if (staging.capacity() < length) staging = ByteBuffer.allocateDirect(length)
        val frames = length / frameBytes
        val count = frames * processor.channelCount
        if (samples.size < count) samples = FloatArray(count)
        val input = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        read(input, count)
        processor.process(samples, frames)
        val output = staging.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        output.clear()
        write(output, count)
        // A trailing partial frame, which the sink does not take anyway, is copied as it is.
        while (input.hasRemaining()) output.put(input.get())
        output.flip()
        return output
    }

    private fun read(
        input: ByteBuffer,
        count: Int,
    ) {
        when (format) {
            AndroidPcmSampleFormat.Unsigned8 ->
                for (index in 0 until count) samples[index] = ((input.get().toInt() and 0xff) - U8_ZERO) / U8_SCALE
            AndroidPcmSampleFormat.Signed16 -> for (index in 0 until count) {
                samples[index] =
                    input.getShort() / S16_SCALE
            }
            AndroidPcmSampleFormat.Signed24Packed ->
                for (index in 0 until count) {
                    val low = input.get().toInt() and 0xff
                    val middle = input.get().toInt() and 0xff
                    // The top byte is read signed, which sign-extends the 24-bit value.
                    samples[index] = (low or (middle shl 8) or (input.get().toInt() shl 16)) / S24_SCALE
                }
            AndroidPcmSampleFormat.Signed32 ->
                for (index in 0 until count) samples[index] = (input.getInt() / S32_SCALE).toFloat()
            AndroidPcmSampleFormat.Float32 -> for (index in 0 until count) samples[index] = input.getFloat()
        }
    }

    private fun write(
        output: ByteBuffer,
        count: Int,
    ) {
        when (format) {
            AndroidPcmSampleFormat.Unsigned8 ->
                for (index in 0 until count) {
                    output.put(((samples[index] * U8_SCALE).roundToInt() + U8_ZERO).coerceIn(0, 255).toByte())
                }
            AndroidPcmSampleFormat.Signed16 ->
                for (index in 0 until count) {
                    output.putShort((samples[index] * S16_SCALE).roundToInt().coerceIn(-32_768, 32_767).toShort())
                }
            AndroidPcmSampleFormat.Signed24Packed ->
                for (index in 0 until count) {
                    val value = (samples[index] * S24_SCALE).roundToInt().coerceIn(-8_388_608, 8_388_607)
                    output.put(value.toByte())
                    output.put((value shr 8).toByte())
                    output.put((value shr 16).toByte())
                }
            AndroidPcmSampleFormat.Signed32 ->
                for (index in 0 until count) {
                    val value = (samples[index] * S32_SCALE).roundToLong()
                    output.putInt(value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                }
            AndroidPcmSampleFormat.Float32 -> for (index in 0 until count) output.putFloat(samples[index])
        }
    }
}

private const val U8_ZERO = 128
private const val U8_SCALE = 128f
private const val S16_SCALE = 32_768f
private const val S24_SCALE = 8_388_608f
private const val S32_SCALE = 2_147_483_648.0
