package com.yfuse.core2.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SoftwarePcmOutputTest {
    private fun frame(
        rate: Int = 48_000,
        channels: Int = 2,
    ) = YSoftwareAudioDecodeResult.Frame(
        ByteBuffer
            .allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.25f)
            .putFloat(-0.25f)
            .apply { flip() },
        1_000_000,
        channels,
        rate,
        1,
        AndroidPcmSampleFormat.Float32,
    )

    @Test
    fun floatHasNoQuantizationAndRateOrChannelChangesRequireRenegotiation() {
        val output = SoftwarePcmOutput(true)
        val original = frame()
        output.configure(original) { assertEquals(AndroidPcmSampleFormat.Float32, it) }
        assertSame(original, output.convert(original))
        assertTrue(output.matches(original))
        assertFalse(output.matches(frame(rate = 44_100)))
        assertFalse(output.matches(frame(channels = 6)))
        output.release()
        assertFalse(output.matches(original))
    }

    @Test
    fun onlyUnsupportedFormatsFallBackAndNegotiationStopsAtFirstAcceptedFormat() {
        val output = SoftwarePcmOutput(true)
        val attempted = mutableListOf<AndroidPcmSampleFormat>()
        output.configure(frame()) {
            attempted += it
            if (it == AndroidPcmSampleFormat.Float32) throw IllegalArgumentException("Unsupported float sink")
        }
        assertEquals(listOf(AndroidPcmSampleFormat.Float32, AndroidPcmSampleFormat.Signed24Packed), attempted)
        output.release()
    }

    @Test
    fun integerFallbackPreservesTheSourceAndClampsFullScaleWithoutWrapping() {
        val input =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(-1f)
                .putFloat(1f)
                .putFloat(Float.NaN)
                .putFloat(1f / 65_536f)
                .apply { flip() }
        val target = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        convertFloatPcm(input, target, AndroidPcmSampleFormat.Signed24Packed, Random(1))
        assertEquals(0, input.position())
        target.flip()

        fun sample(): Int =
            (target.get().toInt() and 255) or ((target.get().toInt() and 255) shl 8) or (target.get().toInt() shl 16)
        assertTrue(sample() in -8_388_608..-8_388_607)
        assertEquals(8_388_607, sample())
        assertTrue(sample() in -1..1)
        assertTrue(sample() in 127..129) // A detail below 16-bit resolution is retained.
    }
}
