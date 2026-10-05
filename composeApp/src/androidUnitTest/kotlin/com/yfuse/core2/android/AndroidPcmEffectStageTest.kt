package com.yfuse.core2.android

import com.yfuse.core2.api.YAudioEffect
import com.yfuse.core2.audio.YAudioChannelRole
import com.yfuse.core2.audio.YAudioEffectProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class AndroidPcmEffectStageTest {
    @Test
    fun the_decoder_buffer_is_left_as_it_is_and_the_copy_carries_the_effect() {
        val stage = boostStage(AndroidPcmSampleFormat.Signed16)
        val data = shorts(1_000, -2_000, 4_000, -8_000).asReadOnlyBuffer()

        val copy = stage.input(data)

        assertEquals(listOf(1_500, -3_000, 6_000, -12_000), copy.shortsLeft())
        assertEquals(0, data.position())
        assertEquals(1_000, data.order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt())
    }

    @Test
    fun a_remainder_offered_again_continues_from_the_copy_instead_of_being_boosted_twice() {
        val stage = boostStage(AndroidPcmSampleFormat.Signed16)
        val data = shorts(1_000, -2_000, 4_000, -8_000)

        // The sink takes one stereo frame of two.
        val copy = stage.input(data)
        copy.position(copy.position() + 4)
        stage.consumed(data, 4)

        val again = stage.input(data)
        assertSame(copy, again)
        assertEquals(4, data.position())
        assertEquals(listOf(6_000, -12_000), again.shortsLeft())

        again.position(again.limit())
        stage.consumed(data, 4)
        // Fully written: the next buffer, even the same object refilled, is processed afresh.
        data.clear()
        assertNotSame(again, stage.input(data))
    }

    @Test
    fun a_flush_forgets_the_part_written_copy() {
        val stage = boostStage(AndroidPcmSampleFormat.Signed16)
        val data = shorts(1_000, 1_000)
        val copy = stage.input(data)

        stage.reset()

        assertNotSame(copy, stage.input(data))
    }

    @Test
    fun every_pcm_encoding_round_trips_through_the_processor() {
        val float = boostStage(AndroidPcmSampleFormat.Float32).input(floats(0.25f, -0.5f))
        assertEquals(listOf(0.375f, -0.75f), List(2) { float.getFloat() })

        val packed = boostStage(AndroidPcmSampleFormat.Signed24Packed).input(bytes(0x00, 0x00, 0x10, 0x00, 0x00, 0xF0))
        // 0x100000 and -0x100000, times 1.5, little-endian in three bytes each.
        assertEquals(listOf(0x00, 0x00, 0x18, 0x00, 0x00, 0xE8), List(6) { packed.get().toInt() and 0xff })

        val unsigned = boostStage(AndroidPcmSampleFormat.Unsigned8).input(bytes(168, 88))
        assertEquals(listOf(188, 68), List(2) { unsigned.get().toInt() and 0xff })

        val ints =
            ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1 shl 28)
                .putInt(-(1 shl 28))
        ints.flip()
        val wide = boostStage(AndroidPcmSampleFormat.Signed32).input(ints)
        assertEquals(listOf(3 shl 27, -(3 shl 27)), List(2) { wide.getInt() })
    }

    @Test
    fun a_trailing_partial_frame_is_copied_untouched() {
        val stage = boostStage(AndroidPcmSampleFormat.Signed16)
        val data =
            ByteBuffer
                .allocate(6)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(1_000)
                .putShort(1_000)
                .put(7)
                .put(9)
        data.flip()

        val copy = stage.input(data)

        assertEquals(6, copy.remaining())
        assertEquals(listOf(1_500, 1_500), List(2) { copy.order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() })
        assertEquals(listOf<Byte>(7, 9), listOf(copy.get(), copy.get()))
    }

    private fun boostStage(format: AndroidPcmSampleFormat) =
        AndroidPcmEffectStage(
            YAudioEffectProcessor(
                YAudioEffect.VolumeBoost,
                48_000,
                if (format == AndroidPcmSampleFormat.Signed16) {
                    listOf(YAudioChannelRole.Front, YAudioChannelRole.Front)
                } else {
                    listOf(YAudioChannelRole.Front)
                },
            ),
            format,
        )

    private fun shorts(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun floats(vararg values: Float): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        buffer.flip()
        return buffer
    }

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

    private fun ByteBuffer.shortsLeft(): List<Int> {
        val view = duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return List(view.remaining() / 2) { view.getShort().toInt() }
    }
}
