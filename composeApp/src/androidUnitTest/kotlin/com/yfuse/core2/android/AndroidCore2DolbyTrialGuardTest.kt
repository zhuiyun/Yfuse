package com.yfuse.core2.android

import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCore2DolbyTrialGuardTest {
    @Test
    fun unknown_dolby_profile_stays_on_legacy_even_when_old_metadata_marked_it_safe() {
        assertFalse(
            listOf(
                item(
                    dolbyProfile = null,
                    needsDolbyDecoder = false,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    @Test
    fun dolby_profile_5_stays_on_legacy() {
        assertFalse(
            listOf(
                item(
                    dolbyProfile = 5,
                    needsDolbyDecoder = true,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    @Test
    fun dolby_profile_8_with_compatible_base_layer_remains_trial_eligible() {
        assertTrue(
            listOf(
                item(
                    dolbyProfile = 8,
                    needsDolbyDecoder = false,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    private fun item(
        dolbyProfile: Int?,
        needsDolbyDecoder: Boolean,
    ): PlayerMediaItem {
        val url = "https://media.example.test/movie.mkv"
        val version =
            PlayerMediaVersion(
                id = "dv-${dolbyProfile ?: "unknown"}",
                label = "Dolby Vision",
                detail = "DV",
                url = url,
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                dolbyVision = true,
                dolbyProfile = dolbyProfile,
                needsDolbyDecoder = needsDolbyDecoder,
            )
        return PlayerMediaItem(
            id = "movie",
            url = url,
            transcodeUrl = "",
            title = "Movie",
            versions = listOf(version),
            versionId = version.id,
        )
    }
}
