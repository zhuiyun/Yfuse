package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class ProResPlaybackTruthTest {
    @Test
    fun emby_prores_codec_name_is_preserved_in_the_fast_probe() {
        val version =
            PlayerMediaVersion(
                id = "source-1",
                label = "1080p ProRes 12-bit",
                detail = "MOV",
                url = "https://example.invalid/original.mov",
                transcodeUrl = "https://example.invalid/transcode.m3u8",
                fallbackTranscodeUrl = "https://example.invalid/transcode.mp4",
                container = "MOV",
                sourceWidth = 1_920,
                sourceHeight = 1_080,
                sourceVideoCodec = "prores_ks",
                sourceFrameRate = 24.0,
                sourceBitDepth = 12,
            )

        val requirements = version.sourceRequirements()

        assertEquals(PlaybackVideoCodec.ProRes, requirements.videoCodec)
        assertEquals(12, requirements.bitDepth)
    }
}
