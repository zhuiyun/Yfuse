package com.yfuse.core2.android

import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCore2TrialTest {
    @Test
    fun ordinary_supported_queue_is_trial_eligible() {
        val items =
            listOf(
                mediaItem("https://media.example.test/movie.mkv"),
                mediaItem("content://media/external/video/1"),
            )

        assertTrue(items.canUseCore2Trial(startIndex = 0))
        assertTrue(items.canUseCore2Trial(startIndex = 1))
    }

    @Test
    fun invalid_queue_or_unknown_source_scheme_stays_on_legacy() {
        assertFalse(emptyList<PlayerMediaItem>().canUseCore2Trial(startIndex = 0))
        assertFalse(listOf(mediaItem("https://media/movie")).canUseCore2Trial(startIndex = 1))
        assertFalse(listOf(mediaItem("ftp://media/movie")).canUseCore2Trial(startIndex = 0))
    }

    @Test
    fun unsupported_queue_features_stay_on_legacy() {
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
        assertFalse(listOf(subtitleItem).canUseCore2Trial(startIndex = 0))
        assertFalse(listOf(discItem).canUseCore2Trial(startIndex = 0))
        assertFalse(
            listOf(mediaItem("https://media/movie"), subtitleItem)
                .canUseCore2Trial(startIndex = 0),
        )
    }

    private fun mediaItem(url: String) =
        PlayerMediaItem(
            id = url,
            url = url,
            transcodeUrl = url,
            title = "Test media",
        )
}
