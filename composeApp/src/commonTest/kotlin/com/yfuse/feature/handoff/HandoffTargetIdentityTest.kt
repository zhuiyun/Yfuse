package com.yfuse.feature.handoff

import com.yfuse.core.handoff.HandoffMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HandoffTargetIdentityTest {
    private val media =
        HandoffMedia(
            "tmdb:1",
            "Movie",
            "server",
            "item",
            1_000,
            60_000,
            mediaSourceId = "version",
            audioTrackIndex = 2,
            subtitleTrackIndex = 3,
            secondarySubtitleTrackIndex = 4,
            subtitleOffsetMs = 150,
            audioOffsetMs = -200,
            secondarySubtitleOffsetMs = 300,
        )

    @Test
    fun onlyOriginalServerItemAndKnownVersionKeepTrackIndexes() {
        assertEquals(media, media.forHandoffTarget("server", "item", "tmdb:1", "version"))
        for (target in listOf(
            media.forHandoffTarget("other-server", "item", "tmdb:1", "version"),
            media.forHandoffTarget("server", "other-item", "tmdb:1", "version"),
            media.forHandoffTarget("server", "item", "tmdb:1", "other-version"),
            media.copy(mediaSourceId = null).forHandoffTarget("server", "item", "tmdb:1", null),
        )) {
            assertNull(target.audioTrackIndex)
            assertNull(target.subtitleTrackIndex)
            assertNull(target.secondarySubtitleTrackIndex)
            assertEquals(150L, target.subtitleOffsetMs)
            assertEquals(-200L, target.audioOffsetMs)
            assertEquals(300L, target.secondarySubtitleOffsetMs)
        }
    }
}
