package com.yfuse.feature.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExoMainFeatureSupportTest {
    @Test
    fun serverResolvedM2tsUsesMpegTsExtractorWithoutAnExtension() {
        assertEquals(
            MimeTypes.VIDEO_MP2T,
            exoContainerMimeType("m2ts", "https://media.example.test/Videos/stream", true),
        )
        assertEquals(
            MimeTypes.VIDEO_MP2T,
            exoContainerMimeType(null, "https://media.example.test/00042.m2ts?token=x", false),
        )
        assertNull(exoContainerMimeType("m2ts", "https://media.example.test/master.m3u8", false))
    }

    @Test
    fun hdr10PlusIsReportedOnlyWithPqAndServerSignal() {
        val pq =
            Format
                .Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setColorInfo(
                    ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build(),
                ).build()

        assertEquals("HDR10+ / PQ", pq.dynamicRangeLabel("HDR10+"))
        assertEquals("HDR10 / PQ", pq.dynamicRangeLabel("HDR10"))
    }

    @Test
    fun trueHdAndEac3UseTheLocalSafetyFallback() {
        assertTrue(MimeTypes.AUDIO_TRUEHD.requiresExoDolbyAudioSafetyFallback())
        assertTrue(MimeTypes.AUDIO_E_AC3.requiresExoDolbyAudioSafetyFallback())
        assertTrue(MimeTypes.AUDIO_E_AC3_JOC.requiresExoDolbyAudioSafetyFallback())
        assertFalse(MimeTypes.AUDIO_AAC.requiresExoDolbyAudioSafetyFallback())
    }
}
