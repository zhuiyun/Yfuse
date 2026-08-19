package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioTrackMatchingTest {
    @Test
    fun chineseDisplayLanguageMatchesEngineIsoCode() {
        val tracks =
            listOf(
                EngineTrack(id = "1", label = "English AC-3", language = "eng", selected = false),
                EngineTrack(id = "2", label = "中文 AAC", language = "zho", selected = true),
            )

        assertEquals("1", tracks.matchingLanguage("英语"))
        assertEquals("2", tracks.matchingLanguage("中文"))
    }

    @Test
    fun trackTitleRemainsTheFallbackWhenLanguageIsMissing() {
        val tracks =
            listOf(
                EngineTrack(id = "3", label = "国语 Dolby TrueHD", language = null, selected = true),
            )

        assertEquals("3", tracks.matchingLanguage("国语"))
        assertNull(tracks.matchingLanguage("日语"))
    }

    @Test
    fun unsupportedAudioIsDetectedEvenWhenVideoIsSupported() {
        assertEquals(
            UnsupportedMediaTrack.Audio,
            unsupportedMediaTrack(
                hasVideo = true,
                videoSupported = true,
                hasAudio = true,
                audioSupported = false,
            ),
        )
        assertNull(
            unsupportedMediaTrack(
                hasVideo = true,
                videoSupported = true,
                hasAudio = true,
                audioSupported = true,
            ),
        )
    }

    @Test
    fun unsupportedAudioPrefersNativeEngineInsteadOfServerTranscode() {
        assertEquals(
            UnsupportedTrackRecovery.SwitchEngine,
            unsupportedTrackRecovery(
                track = UnsupportedMediaTrack.Audio,
                alreadyTranscoding = false,
            ),
        )
        assertEquals(
            UnsupportedTrackRecovery.SwitchEngine,
            unsupportedTrackRecovery(
                track = UnsupportedMediaTrack.Audio,
                alreadyTranscoding = true,
            ),
        )
    }

    @Test
    fun unsupportedDirectVideoStillUsesServerTranscode() {
        assertEquals(
            UnsupportedTrackRecovery.ServerTranscode,
            unsupportedTrackRecovery(
                track = UnsupportedMediaTrack.Video,
                alreadyTranscoding = false,
            ),
        )
        assertEquals(
            UnsupportedTrackRecovery.SwitchEngine,
            unsupportedTrackRecovery(
                track = UnsupportedMediaTrack.Video,
                alreadyTranscoding = true,
            ),
        )
    }
}
