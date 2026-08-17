package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.PlaybackVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class ProResPlaybackTruthTest {
    @Test
    fun emby_prores_ks_fast_metadata_preserves_codec_and_12bit_depth() {
        val version =
            PlayerMediaVersion(
                id = "prores-source",
                label = "1080p ProRes 12-bit",
                detail = "182 GB · MOV",
                url = "https://media.example/movie.mov",
                transcodeUrl = "https://media.example/master.m3u8",
                fallbackTranscodeUrl = "https://media.example/fallback.mp4",
                container = "MOV",
                sourceWidth = 1920,
                sourceHeight = 1080,
                sourceVideoCodec = "prores_ks",
                sourceFrameRate = 24.0,
                sourceBitDepth = 12,
                sourceAudio = "PCM 24-bit",
                playMethod = PlaybackMethod.DirectPlay,
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
                playMethod = PlaybackMethod.DirectPlay,
            )

        val requirements = item.playbackMediaProbe().source.videoRequirements

        assertEquals(PlaybackVideoCodec.ProRes, requirements.codec)
        assertEquals(12, requirements.bitDepth)
        assertEquals(1920, requirements.width)
        assertEquals(1080, requirements.height)
        assertEquals(24.0, requirements.frameRate)
    }
}
