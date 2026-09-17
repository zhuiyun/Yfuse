package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStateFanoutTest {
    @Test
    fun position_ticks_do_not_reach_the_presentation_consumer() {
        val presentation = mutableListOf<PlaybackState>()
        val progress = mutableListOf<PlaybackState>()
        val fanout =
            PlaybackStateFanout(
                onPresentationChange = { state, _ -> presentation += state },
                onProgress = { state, _ -> progress += state },
            )
        val playing = PlaybackState(playing = true, buffering = false, durationMs = 60_000L, itemCount = 3)

        // Twenty 500 ms ticks: only the position and buffer depth move.
        repeat(20) { tick ->
            fanout.dispatch(
                playing.copy(
                    positionMs = tick * 500L,
                    bufferedPositionMs = tick * 500L + 4_000L,
                    diagnostics = playing.diagnostics.copy(bufferedDurationMs = 4_000L + tick),
                ),
                null,
            )
        }

        assertEquals(1, presentation.size, "notification/media-session consumer must see the first state only")
        assertEquals(20, progress.size)
        assertEquals(0L, presentation.single().positionMs)
        assertEquals(19 * 500L, progress.last().positionMs)
    }

    @Test
    fun transport_index_and_geometry_changes_reach_the_presentation_consumer() {
        val presentation = mutableListOf<PlaybackState>()
        val fanout =
            PlaybackStateFanout(
                onPresentationChange = { state, _ -> presentation += state },
                onProgress = { _, _ -> },
            )
        val base = PlaybackState(playing = true, buffering = false, durationMs = 60_000L, itemCount = 3)
        fanout.dispatch(base, null)
        fanout.dispatch(base.copy(positionMs = 500L), null)
        fanout.dispatch(base.copy(positionMs = 1_000L, playing = false), null)
        fanout.dispatch(base.copy(positionMs = 1_500L, playing = false), null)
        fanout.dispatch(base.copy(positionMs = 0L, currentIndex = 1), null)
        fanout.dispatch(base.copy(positionMs = 500L, currentIndex = 1, videoHeight = 1080), null)
        fanout.dispatch(base.copy(positionMs = 1_000L, currentIndex = 1, videoHeight = 1080, error = "boom"), null)

        assertEquals(
            listOf(0L, 1_000L, 0L, 500L, 1_000L),
            presentation.map { it.positionMs },
            "start, pause, item change, geometry, error",
        )
    }

    @Test
    fun progress_consumer_always_runs_after_the_presentation_consumer_for_the_same_state() {
        val order = mutableListOf<String>()
        val fanout =
            PlaybackStateFanout(
                onPresentationChange = { _, _ -> order += "presentation" },
                onProgress = { _, _ -> order += "progress" },
            )
        fanout.dispatch(PlaybackState(), null)
        fanout.dispatch(PlaybackState(positionMs = 500L), null)
        assertEquals(listOf("presentation", "progress", "progress"), order)
    }

    @Test
    fun media_session_position_is_republished_on_the_interval_or_after_a_seek() {
        val sync = MediaSessionPositionSync(intervalMs = 10_000L, toleranceMs = 1_500L)
        val playing = PlaybackState(playing = true, buffering = false, speed = 1f)

        assertTrue(sync.shouldPublish(playing, nowElapsedMs = 0L), "nothing published yet")
        sync.published(playing.copy(positionMs = 0L), nowElapsedMs = 0L)

        // Steady playback inside the interval extrapolates cleanly.
        assertFalse(sync.shouldPublish(playing.copy(positionMs = 500L), nowElapsedMs = 500L))
        assertFalse(sync.shouldPublish(playing.copy(positionMs = 9_500L), nowElapsedMs = 9_500L))
        // The interval elapsed.
        assertTrue(sync.shouldPublish(playing.copy(positionMs = 10_000L), nowElapsedMs = 10_000L))
        // A seek lands far from the extrapolated position before the interval elapses.
        assertTrue(sync.shouldPublish(playing.copy(positionMs = 30_000L), nowElapsedMs = 2_000L))

        // Paused: the extrapolation stands still, so a moving position is a seek.
        val paused = playing.copy(playing = false, positionMs = 5_000L)
        sync.published(paused, nowElapsedMs = 20_000L)
        assertFalse(sync.shouldPublish(paused, nowElapsedMs = 25_000L))
        assertTrue(sync.shouldPublish(paused.copy(positionMs = 8_000L), nowElapsedMs = 25_000L))
    }
}
