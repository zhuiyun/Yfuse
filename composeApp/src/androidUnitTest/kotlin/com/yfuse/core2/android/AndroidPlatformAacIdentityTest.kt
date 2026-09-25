package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlatformAacIdentityTest {
    @Test
    fun `dts in an mp4a entry is not taken for aac`() {
        // The platform MP4 extractor names FFmpeg's DTS-in-MP4 track audio/mp4a-latm and gives it an
        // empty (or no) csd-0. NativeDirect chose it over the real AAC track and played silence.
        assertTrue(platformAacLabelWithoutConfig("audio/mp4a-latm", audioSpecificConfigBytes = 0, adts = false))
        assertTrue(platformAacLabelWithoutConfig("audio/mp4a-latm", audioSpecificConfigBytes = null, adts = false))
        assertTrue(platformAacLabelWithoutConfig("Audio/MP4A-LATM", audioSpecificConfigBytes = 1, adts = false))
    }

    @Test
    fun `aac with its audio specific config stays aac`() {
        assertFalse(platformAacLabelWithoutConfig("audio/mp4a-latm", audioSpecificConfigBytes = 2, adts = false))
        assertFalse(platformAacLabelWithoutConfig("audio/mp4a-latm", audioSpecificConfigBytes = 5, adts = false))
    }

    @Test
    fun `adts configures itself`() {
        assertFalse(platformAacLabelWithoutConfig("audio/mp4a-latm", audioSpecificConfigBytes = null, adts = true))
        assertFalse(platformAacLabelWithoutConfig("audio/aac-adts", audioSpecificConfigBytes = null, adts = false))
    }

    @Test
    fun `other codecs never need an audio specific config`() {
        assertFalse(platformAacLabelWithoutConfig("audio/eac3", audioSpecificConfigBytes = null, adts = false))
        assertFalse(platformAacLabelWithoutConfig("audio/vnd.dts", audioSpecificConfigBytes = null, adts = false))
        assertFalse(platformAacLabelWithoutConfig("audio/mpeg", audioSpecificConfigBytes = null, adts = false))
        assertFalse(platformAacLabelWithoutConfig(null, audioSpecificConfigBytes = null, adts = false))
    }
}
