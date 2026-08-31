package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSkipCoordinatorTest {
    @Test
    fun `movie does not expose intro or outro skipping`() {
        assertFalse(skipSegmentsAvailableFor(null))
        assertFalse(skipSegmentsAvailableFor(""))
    }

    @Test
    fun `episode keeps intro and outro skipping`() {
        assertTrue(skipSegmentsAvailableFor("series-1"))
    }

    @Test
    fun `manual skip prompt follows playback control visibility`() {
        assertTrue(
            shouldShowManualSkipPill(
                segmentLabel = "跳过片头",
                countdownSeconds = null,
                controlsVisible = true,
            ),
        )
        assertFalse(
            shouldShowManualSkipPill(
                segmentLabel = "跳过片头",
                countdownSeconds = null,
                controlsVisible = false,
            ),
        )
        assertFalse(
            shouldShowManualSkipPill(
                segmentLabel = "跳过片头",
                countdownSeconds = 3,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun `automatic skip waits until playback is ready`() {
        assertFalse(
            canArmAutomaticSkip(
                segmentType = PlaybackSegmentType.Intro,
                playbackReady = false,
                creditsEnteredFromPlayback = true,
            ),
        )
        assertTrue(
            canArmAutomaticSkip(
                segmentType = PlaybackSegmentType.Intro,
                playbackReady = true,
                creditsEnteredFromPlayback = false,
            ),
        )
    }

    @Test
    fun `resume inside credits cannot automatically advance the queue`() {
        assertFalse(
            canArmAutomaticSkip(
                segmentType = PlaybackSegmentType.Credits,
                playbackReady = true,
                creditsEnteredFromPlayback = false,
            ),
        )
        assertTrue(
            canArmAutomaticSkip(
                segmentType = PlaybackSegmentType.Credits,
                playbackReady = true,
                creditsEnteredFromPlayback = true,
            ),
        )
    }

    @Test
    fun `launch snapshot does not count as entering credits from playback`() {
        assertFalse(
            observedForwardPlaybackOutsideCredits(
                previousPositionMs = null,
                positionMs = 0L,
                segmentType = null,
                playbackReady = true,
            ),
        )
        assertTrue(
            observedForwardPlaybackOutsideCredits(
                previousPositionMs = 500L,
                positionMs = 1_000L,
                segmentType = null,
                playbackReady = true,
            ),
        )
        assertFalse(
            observedForwardPlaybackOutsideCredits(
                previousPositionMs = 500L,
                positionMs = 120_000L,
                segmentType = PlaybackSegmentType.Credits,
                playbackReady = true,
            ),
        )
    }
}
