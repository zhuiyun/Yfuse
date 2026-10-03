package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun profile_carries_the_container_size_and_runtime_a_dlna_renderer_is_told() {
        val version =
            PlayerMediaVersion(
                id = "hevc",
                label = "1080p",
                detail = "HDR10 · MKV",
                url = "https://media.example.test/Videos/42/stream?static=true",
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                container = "MKV",
                sourceSizeBytes = 4_655_267_216L,
            )
        val item =
            PlayerMediaItem(
                id = "42",
                url = version.url,
                transcodeUrl = "",
                title = "Episode",
                versions = listOf(version),
                versionId = version.id,
                durationMsHint = 2_470_504L,
            )

        val profile = item.castMediaProfile()

        assertEquals("MKV", profile.container)
        assertEquals(4_655_267_216L, profile.sizeBytes)
        assertEquals(2_470_504L, profile.durationMs)
        val unknown = PlayerMediaItem(id = "x", url = "u", transcodeUrl = "", title = "t")
        assertNull(unknown.castMediaProfile().durationMs)
        assertNull(unknown.castMediaProfile().container)
    }
}
