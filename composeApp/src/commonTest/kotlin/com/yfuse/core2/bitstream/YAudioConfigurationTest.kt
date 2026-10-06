package com.yfuse.core2.bitstream

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
