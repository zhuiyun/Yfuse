package com.yfuse.core2.android

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCore2TrialTest {
    @Test
    fun supported_file_and_native_disc_schemes_are_trial_eligible() {
        val items =
            listOf(
                mediaItem("https://media.example.test/movie.mkv"),
                mediaItem("smb://nas.example.test/Movies/movie.mkv"),
                mediaItem("webdavs://dav.example.test/Movies/movie.mkv"),
                mediaItem("content://media/external/video/1"),
                mediaItem("yfusebdmv://42"),
            )

        assertTrue(items.canUseCore2Trial(startIndex = 0))
        assertTrue(items.canUseCore2Trial(startIndex = 1))
        assertTrue(items.canUseCore2Trial(startIndex = 2))
        assertTrue(items.canUseCore2Trial(startIndex = 3))
        assertTrue(items.canUseCore2Trial(startIndex = 4))
    }

    @Test
    fun invalid_queue_or_unknown_source_scheme_stays_on_legacy() {
        assertFalse(emptyList<PlayerMediaItem>().canUseCore2Trial(startIndex = 0))
        assertFalse(listOf(mediaItem("https://media/movie")).canUseCore2Trial(startIndex = 1))
        assertFalse(listOf(mediaItem("ftp://media/movie")).canUseCore2Trial(startIndex = 0))
    }

    @Test
    fun drm_and_unsupported_subtitle_sources_stay_on_legacy() {
        val drmItem =
            mediaItem("https://media.example.test/secure.mpd").copy(
                drmConfiguration =
                    PlaybackDrmConfiguration(
                        scheme = PlaybackDrmScheme.Widevine,
                        licenseUri = "https://license.example.test/widevine",
                    ),
            )
        val subtitleItem =
            mediaItem("file:///offline/movie.mkv").copy(
                externalSubtitleUri = "file:///offline/movie.srt",
            )
        val unsupportedSubtitleItem =
            subtitleItem.copy(externalSubtitleUri = "ftp://media.example.test/movie.srt")
        val discVersion =
            PlayerMediaVersion(
                id = "disc",
                label = "Blu-ray",
                detail = "BDMV",
                url = "file:///storage/movie/BDMV",
                transcodeUrl = "https://media.example.test/transcode.m3u8",
                fallbackTranscodeUrl = "https://media.example.test/fallback.m3u8",
                discSource = true,
            )
        val discItem =
            mediaItem(discVersion.url).copy(
                versions = listOf(discVersion),
                versionId = discVersion.id,
            )

        assertFalse(listOf(drmItem).canUseCore2Trial(startIndex = 0))
        assertTrue(listOf(subtitleItem).canUseCore2Trial(startIndex = 0))
        assertFalse(listOf(unsupportedSubtitleItem).canUseCore2Trial(startIndex = 0))
        assertTrue(listOf(discItem).canUseCore2Trial(startIndex = 0))
        assertTrue(
            listOf(mediaItem("https://media/movie"), subtitleItem)
                .canUseCore2Trial(startIndex = 0),
        )
    }

    @Test
    fun unproven_dolby_vision_stream_stays_on_the_display_backed_legacy_pipeline() {
        val version =
            PlayerMediaVersion(
                id = "dolby",
                label = "Dolby Vision",
                detail = "HEVC Dolby Vision",
                url = "https://media.example.test/dolby.mkv",
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                dolbyVision = true,
                dolbyProfile = null,
                needsDolbyDecoder = false,
            )
        val item =
            mediaItem(version.url).copy(
                versions = listOf(version),
                versionId = version.id,
            )

        assertFalse(listOf(item).canUseCore2Trial(startIndex = 0))
    }

    @Test
    fun core2_queue_mapping_preserves_request_identity_and_user_agent() {
        val item =
            mediaItem("https://media.example.test/episode.mkv").copy(
                id = "episode-2",
                title = "Episode 2",
                serverId = "server-a",
                externalSubtitleUri = "content://offline/subtitle/2",
                externalSubtitleLanguage = "zh-CN",
            )

        val mapped =
            listOf(item)
                .toCore2MediaItems("  Yfuse-Test/2.0  ", PlaybackQuality.Original)
                .single()

        assertEquals(item.id, mapped.id)
        assertEquals(item.url, mapped.uri)
        assertEquals(item.title, mapped.title)
        assertEquals(item.serverId, mapped.providerKey)
        assertEquals("Yfuse-Test/2.0", mapped.headers["User-Agent"])
        assertEquals(item.externalSubtitleUri, mapped.externalSubtitle?.uri)
        assertEquals(item.externalSubtitleLanguage, mapped.externalSubtitle?.language)
    }

    @Test
    fun core2_queue_mapping_uses_the_active_server_transcode_url() {
        val item =
            mediaItem("https://media.example.test/original.mkv").copy(
                transcodeUrl = "https://media.example.test/transcode.m3u8",
                playMethod = PlaybackMethod.Transcode,
            )

        val mapped =
            listOf(item)
                .toCore2MediaItems("", PlaybackQuality.Original)
                .single()

        assertEquals(item.transcodeUrl, mapped.uri)
        assertEquals(null, mapped.disc)
    }

    @Test
    fun server_resolved_disc_stream_does_not_expose_native_navigation() {
        val version =
            PlayerMediaVersion(
                id = "disc",
                label = "Blu-ray",
                detail = "BDMV",
                url = "file:///storage/movie/BDMV",
                transcodeUrl = "https://media.example.test/transcode.m3u8",
                fallbackTranscodeUrl = "https://media.example.test/fallback.mp4",
                container = "BDMV",
                discSource = true,
                playMethod = PlaybackMethod.Transcode,
            )
        val item =
            mediaItem(version.transcodeUrl).copy(
                transcodeUrl = version.transcodeUrl,
                playMethod = PlaybackMethod.Transcode,
                versions = listOf(version),
                versionId = version.id,
            )

        val mapped =
            listOf(item)
                .toCore2MediaItems("", PlaybackQuality.Original)
                .single()

        assertEquals(version.transcodeUrl, mapped.uri)
        assertEquals(null, mapped.disc)
    }

    @Test
    fun core2_queue_mapping_preserves_direct_disc_identity() {
        val version =
            PlayerMediaVersion(
                id = "disc",
                label = "Blu-ray",
                detail = "BDMV",
                url = "yfusebdmv://42",
                transcodeUrl = "https://media.example.test/transcode.m3u8",
                fallbackTranscodeUrl = "https://media.example.test/fallback.mp4",
                container = "BDMV",
                discSource = true,
            )
        val item =
            mediaItem(version.url).copy(
                versions = listOf(version),
                versionId = version.id,
            )

        val mapped =
            listOf(item)
                .toCore2MediaItems("", PlaybackQuality.Original)
                .single()

        assertEquals(com.yfuse.core2.api.YDiscKind.Bdmv, mapped.disc?.kind)
        assertEquals("BDMV", mapped.disc?.container)
    }

    private fun mediaItem(url: String) =
        PlayerMediaItem(
            id = url,
            url = url,
            transcodeUrl = url,
            title = "Test media",
        )
}
