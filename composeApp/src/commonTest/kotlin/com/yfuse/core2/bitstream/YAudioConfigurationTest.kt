package com.yfuse.core2.bitstream

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YAudioConfigurationTest {
    @Test
    fun `the opus codec delay is the header's pre-skip in nanoseconds`() {
        // Version 1, stereo, pre-skip 312 samples (6.5 ms), 48 kHz input, no gain, family 0.
        val header =
            "OpusHead".encodeToByteArray() +
                byteArrayOf(1, 2, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

        assertEquals(6_500_000L, YAudioConfiguration.opusCodecDelayNs(header))
        assertNull(YAudioConfiguration.opusCodecDelayNs(header.copyOf(18)))
        assertNull(YAudioConfiguration.opusCodecDelayNs("OpusTags".encodeToByteArray() + header.copyOfRange(8, 19)))
    }

    @Test
    fun `aac without configuration is adts`() {
        assertTrue(YAudioConfiguration.aacIsAdts(emptyList()))
        assertTrue(YAudioConfiguration.aacIsAdts(listOf(ByteArray(0))))
        // An AudioSpecificConfig: AAC-LC, 48 kHz, stereo.
        assertFalse(YAudioConfiguration.aacIsAdts(listOf(byteArrayOf(0x11, 0x90.toByte()))))
    }

    @Test
    fun `raw flac streaminfo becomes a complete last metadata block without changing the source`() {
        val streamInfo = ByteArray(34) { it.toByte() }
        val before = streamInfo.copyOf()
        val configured = YAudioConfiguration.flacCodecSpecificData(streamInfo)
        assertEquals(42, configured.size)
        assertContentEquals("fLaC".encodeToByteArray(), configured.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0, 0, 34), configured.copyOfRange(4, 8))
        assertContentEquals(before, configured.copyOfRange(8, configured.size))
        assertContentEquals(before, streamInfo)
    }

    @Test
    fun `complete flac metadata including extra blocks is preserved once`() {
        val header = "fLaC".encodeToByteArray() + byteArrayOf(0, 0, 0, 34) + ByteArray(34)
        val withPadding = header + byteArrayOf(0x81.toByte(), 0, 0, 3, 0, 0, 0)
        assertContentEquals(withPadding, YAudioConfiguration.flacCodecSpecificData(withPadding))
        val single = header.copyOf().apply { this[4] = 0x80.toByte() }
        assertContentEquals(single, YAudioConfiguration.flacCodecSpecificData(single))
    }

    @Test
    fun `truncated or mislabeled flac initialization is rejected before decoding`() {
        val valid = "fLaC".encodeToByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34)
        val invalid =
            listOf(ByteArray(0), ByteArray(33), valid.copyOf(34), valid.copyOf(41), valid.copyOf().apply { this[4] = 1 })
        for (bad in invalid) {
            assertFailsWith<IllegalArgumentException> { YAudioConfiguration.flacCodecSpecificData(bad) }
        }
    }
}
