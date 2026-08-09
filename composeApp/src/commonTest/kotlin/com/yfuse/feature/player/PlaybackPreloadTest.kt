package com.yfuse.feature.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackPreloadTest {

    @Test
    fun next_episode_is_preloaded_only_inside_the_final_90_seconds() = runTest {
        val preloader = RecordingPreloader()
        val reporter = PlaybackProgressReporter(
            items = listOf(
                PlayerMediaItem("e1", "direct-1", "hls-1", "第一集"),
                PlayerMediaItem("e2", "direct-2", "hls-2", "第二集"),
            ),
            sink = NoopSink,
            scope = this,
            sourcePreloader = preloader,
        )

        reporter.update(
            PlaybackState(
                playing = true,
                currentIndex = 0,
                itemCount = 2,
                positionMs = 480_000L,
                durationMs = 600_000L,
            ),
        )
        assertTrue(preloader.urls.isEmpty())

        reporter.update(
            PlaybackState(
                playing = true,
                currentIndex = 0,
                itemCount = 2,
                positionMs = 511_000L,
                durationMs = 600_000L,
            ),
        )
        assertEquals(listOf("direct-2"), preloader.urls)

        // The 500 ms player ticker may call update many times in the window. One source should
        // still be warmed only once.
        reporter.update(
            PlaybackState(
                playing = true,
                currentIndex = 0,
                itemCount = 2,
                positionMs = 540_000L,
                durationMs = 600_000L,
            ),
        )
        assertEquals(listOf("direct-2"), preloader.urls)
    }

    @Test
    fun final_queue_item_has_nothing_to_preload() = runTest {
        val preloader = RecordingPreloader()
        val reporter = PlaybackProgressReporter(
            items = listOf(PlayerMediaItem("e1", "direct-1", "hls-1", "第一集")),
            sink = NoopSink,
            scope = this,
            sourcePreloader = preloader,
        )

        reporter.update(
            PlaybackState(
                playing = true,
                currentIndex = 0,
                itemCount = 1,
                positionMs = 550_000L,
                durationMs = 600_000L,
            ),
        )

        assertTrue(preloader.urls.isEmpty())
    }

    private class RecordingPreloader : PlaybackSourcePreloader {
        val urls = mutableListOf<String>()

        override fun preload(url: String) {
            urls += url
        }
    }

    private object NoopSink : PlaybackEventSink {
        override suspend fun started(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit

        override suspend fun progress(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit

        override suspend fun stopped(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit
    }
}
