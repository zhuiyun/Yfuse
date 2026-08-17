package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UhdBluRayPlaybackTruthTest {
    @Test
    fun negotiated_disc_direct_stream_is_not_mistaken_for_raw_iso_bytes() {
        val version =
            PlayerMediaVersion(
                id = "bluray-source",
                label = "UHD Blu-ray",
                detail = "4K HDR10 · TrueHD Atmos",
                url = "https://example.invalid/videos/main-feature.m2ts",
                transcodeUrl = "https://example.invalid/transcode.m3u8",
                fallbackTranscodeUrl = "https://example.invalid/transcode.mp4",
                container = "BLURAY",
                discSource = true,
                sourceWidth = 3_840,
                sourceHeight = 2_160,
                sourceVideoCodec = "hevc",
                sourceBitDepth = 10,
                sourceDynamicRange = "HDR10",
                sourceAudio = "TrueHD · Atmos · 7.1",
                playMethod = PlaybackMethod.DirectStream,
            )
        val item =
            PlayerMediaItem(
                id = "movie",
                url = version.url,
                transcodeUrl = version.transcodeUrl,
                fallbackTranscodeUrl = version.fallbackTranscodeUrl,
                title = "Movie",
                versions = listOf(version),
                versionId = version.id,
                playMethod = PlaybackMethod.DirectStream,
            )

        val directProbe = item.playbackMediaProbe()
        val transcodeProbe = item.playbackMediaProbe(usingServerTranscode = true)

        assertTrue(directProbe.discMainFeatureResolved)
        assertFalse(directProbe.requiresNativeDemuxer)
        assertFalse(transcodeProbe.discMainFeatureResolved)
    }
}
