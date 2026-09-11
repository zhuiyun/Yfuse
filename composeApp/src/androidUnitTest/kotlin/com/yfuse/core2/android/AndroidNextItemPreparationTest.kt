package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNextItemPreparationTest {
    private val healthy =
        YPlayerState(
            phase = YPlaybackPhase.Ready,
            playing = true,
            durationMs = 1_320_000,
            positionMs = 1_150_000,
            bufferedPositionMs = 1_250_000,
        )

    @Test fun transcode_and_adaptive_sources_are_not_opened_speculatively() {
        val file = YMediaItem("next", "https://server/movie.mkv")
        assertTrue(nextItemSourceEligible(file))
        assertFalse(nextItemSourceEligible(file.copy(allowNextItemPreparation = false)))
        assertFalse(nextItemSourceEligible(file.copy(uri = "https://server/master.m3u8?token=example")))
        assertFalse(nextItemSourceEligible(file.copy(mimeType = "application/dash+xml")))
    }

    @Test fun boundary_uses_credits_and_playback_speed_and_handles_unknown_duration() {
        assertEquals(170_000L, nextItemRemainingMs(healthy, null))
        assertEquals(50_000L, nextItemRemainingMs(healthy, 1_200_000))
        assertEquals(25_000L, nextItemRemainingMs(healthy.copy(speed = 2f), 1_200_000))
        assertEquals(0L, nextItemRemainingMs(healthy.copy(positionMs = 1_220_000), 1_200_000))
        assertEquals(50_000L, nextItemRemainingMs(healthy.copy(durationMs = 0), 1_200_000))
        assertNull(nextItemRemainingMs(healthy.copy(durationMs = 0), null))
        assertNull(nextItemRemainingMs(healthy.copy(speed = Float.NaN), null))
    }

    @Test fun prepared_source_window_tracks_changed_markers_and_does_not_start_while_paused() =
        runTest {
            var state = healthy.copy(playing = false)
            var boundary: Long? = 1_160_000
            val ready = async { awaitNextItemBoundary(20_000, { boundary }, { state }, { true }) }
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(ready.isCompleted)
            boundary = null
            state = healthy
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(ready.isCompleted)
            boundary = 1_160_000
            advanceTimeBy(6_000)
            runCurrent()
            assertTrue(ready.await())
        }

    @Test fun pressure_stalls_and_replaced_children_never_gain_permission_by_timeout() =
        runTest {
            var state: YPlayerState? = healthy
            val ready = async { awaitNextItemBoundary(90_000, { 1_200_000 }, { state }, { false }) }
            advanceTimeBy(120_000)
            runCurrent()
            assertFalse(ready.isCompleted)
            state = null
            advanceTimeBy(250)
            runCurrent()
            assertFalse(ready.await())
            assertFalse(nextItemPlaybackHealthy(healthy.copy(buffering = true)))
            assertFalse(nextItemPlaybackHealthy(healthy.copy(bufferedPositionMs = healthy.positionMs + 1_000)))
        }

    @Test fun byte_budget_is_bounded_even_for_overflowing_or_missing_bitrate() {
        assertEquals(4L * 1024 * 1024, nextItemPreloadBytes(0))
        assertEquals(2L * 1024 * 1024, nextItemPreloadBytes(1))
        assertEquals(12L * 1024 * 1024, nextItemPreloadBytes(Long.MAX_VALUE))
    }
}
