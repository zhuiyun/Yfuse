package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CastMediaProfileTest {
    @Test
    fun profile_uses_original_dolby_codecs_and_does_not_promote_truehd_to_cast_atmos() {
        val version =
            PlayerMediaVersion(
                id = "dolby",
                label = "Dolby",
                detail = "DV + Atmos",
                url = "https://media.example.test/movie.m3u8",
                transcodeUrl = "https://media.example.test/fallback.m3u8",
                fallbackTranscodeUrl = "",
                dolbyVision = true,
                dolbyAtmos = true,
                dolbyProfile = 8,
                sourceAudio = "TrueHD · Atmos · 7.1",
            )
        val item =
            PlayerMediaItem(
                id = "movie",
                url = version.url,
                transcodeUrl = version.transcodeUrl,
                title = "Movie",
                versions = listOf(version),
                versionId = version.id,
            )

        val profile = item.castMediaProfile()

        assertTrue(profile.dolbyVision)
        assertTrue(profile.dolbyAtmos)
        assertEquals("dvh1.08.06", profile.videoCodec)
        assertEquals("truehd", profile.audioCodec)
        assertEquals("application/x-mpegURL", profile.contentType)
    }
}
