package com.yfuse.feature.player

import com.yfuse.core.handoff.HandoffMedia
import com.yfuse.core.sync.playback.PlaybackTrackPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffPreferenceResolutionTest {
    private val audio = EngineTrack("audio", "Original", "eng", true, "aac")
    private val subtitle = EngineTrack("sub", "Chinese", "zho", false, "srt")
    private val media =
        HandoffMedia(
            "tmdb:1",
            "Movie",
            "server",
            "item",
            1_000,
            60_000,
            preference =
                PlaybackTrackPreference(
                    audioLanguage = "eng",
                    audioTitle = "Original",
                    audioCodec = "aac",
                    subtitlesEnabled = true,
                    subtitleLanguage = "zho",
                    subtitleTitle = "Chinese",
                    subtitleCodec = "srt",
                ),
            mediaSourceId = "version",
            audioTrackIndex = 0,
            subtitleTrackIndex = 0,
        )

    @Test
    fun initialReadyWithoutTracksDoesNotConsumeEvenEmptyPreferences() {
        val wait = HandoffPreferenceWait(500)
        assertFalse(wait.resolve(media, emptyList(), emptyList(), false, 500).apply)
        val noTracksRequested = media.copy(preference = null, audioTrackIndex = null, subtitleTrackIndex = null)
        assertFalse(wait.resolve(noTracksRequested, emptyList(), emptyList(), false, 500).apply)
    }

    @Test
    fun waitsForRequestedSubtitleAfterAudioArrivesThenAppliesBoth() {
        val wait = HandoffPreferenceWait(0)
        val partial = wait.resolve(media, listOf(audio), emptyList(), false, 500)
        assertFalse(partial.apply)
        assertEquals(listOf("主字幕"), partial.missing)
        val complete = wait.resolve(media, listOf(audio), listOf(subtitle), false, 1_000)
        assertTrue(complete.apply)
        assertEquals(audio, complete.audio)
        assertEquals(subtitle, complete.subtitle)
        assertTrue(complete.missing.isEmpty())
    }

    @Test
    fun repeatedTrackChangesCannotExtendDeadlineAndPartialResultReportsMissing() {
        val wait = HandoffPreferenceWait(1_000)
        for (time in listOf(2_000L, 5_000L, 9_000L, 10_999L)) {
            assertFalse(wait.resolve(media, listOf(audio.copy(id = "audio-$time")), emptyList(), false, time).apply)
        }
        val expired = wait.resolve(media, listOf(audio), emptyList(), false, 11_000)
        assertTrue(expired.apply)
        assertEquals(audio, expired.audio)
        assertNull(expired.subtitle)
        assertEquals(listOf("主字幕"), expired.missing)
        assertEquals(0L, wait.remainingMs(11_000))
    }

    @Test
    fun secondarySubtitleUnavailableIsBoundedAndReportedWithoutBlockingAudioForever() {
        val requested = media.copy(secondarySubtitlesEnabled = true, secondarySubtitleLanguage = "eng")
        val wait = HandoffPreferenceWait(0)
        assertFalse(wait.resolve(requested, listOf(audio), listOf(subtitle), false, 9_999).apply)
        val expired = wait.resolve(requested, listOf(audio), listOf(subtitle), false, 10_000)
        assertTrue(expired.apply)
        assertEquals(listOf("副字幕"), expired.missing)
    }

    @Test
    fun trustedIndexDisambiguatesIdenticalLabelsButMismatchedMetadataRejectsIndex() {
        val duplicate = audio.copy(id = "second")
        val indexed = media.copy(audioTrackIndex = 1)
        val wait = HandoffPreferenceWait(0)
        assertEquals("second", wait.resolve(indexed, listOf(audio, duplicate), listOf(subtitle), false, 0).audio?.id)
        val differentLanguage = duplicate.copy(language = "fra")
        assertEquals(
            "audio",
            wait.resolve(indexed, listOf(audio, differentLanguage), listOf(subtitle), false, 0).audio?.id,
        )
    }
}
