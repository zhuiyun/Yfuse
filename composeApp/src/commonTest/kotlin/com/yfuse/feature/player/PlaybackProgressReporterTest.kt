package com.yfuse.feature.player

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackProgressReporterTest {

    @Test
    fun reports_start_seek_transition_and_stop_in_order() = runTest {
        val events = mutableListOf<Event>()
        val reporter = PlaybackProgressReporter(
            items = listOf(
                PlayerMediaItem("e1", "direct-1", "transcode-1", "第一集"),
                PlayerMediaItem("e2", "direct-2", "transcode-2", "第二集"),
            ),
            sink = RecordingSink(events),
            scope = this,
        )

        reporter.update(PlaybackState(playing = true, positionMs = 1_000L, currentIndex = 0, itemCount = 2))
        runCurrent()
        reporter.update(PlaybackState(playing = true, positionMs = 7_000L, currentIndex = 0, itemCount = 2))
        runCurrent()
        reporter.update(PlaybackState(playing = true, positionMs = 18_000L, currentIndex = 0, itemCount = 2))
        runCurrent()
        reporter.update(PlaybackState(playing = true, positionMs = 0L, currentIndex = 1, itemCount = 2))
        runCurrent()
        reporter.close(PlaybackState(playing = false, positionMs = 4_000L, currentIndex = 1, itemCount = 2))
        runCurrent()

        assertEquals(
            listOf(
                "started:e1:10000000:false",
                "progress:e1:70000000:false",
                "progress:e1:180000000:false",
                "stopped:e1:180000000:false",
                "started:e2:0:false",
                "progress:e2:40000000:true",
                "stopped:e2:40000000:true",
            ),
            events.map(Event::summary),
        )
        assertEquals(events[0].sessionId, events[1].sessionId)
        assertEquals(events[1].sessionId, events[3].sessionId)
    }

    @Test
    fun natural_end_reports_stopped_without_waiting_for_screen_close() = runTest {
        val events = mutableListOf<Event>()
        val reporter = PlaybackProgressReporter(
            items = listOf(PlayerMediaItem("movie", "direct", "transcode", "电影")),
            sink = RecordingSink(events),
            scope = this,
        )

        reporter.update(PlaybackState(playing = true, positionMs = 2_000L))
        runCurrent()
        reporter.update(
            PlaybackState(
                playing = false,
                positionMs = 7_200_000L,
                durationMs = 7_200_000L,
                buffering = false,
                ended = true,
            ),
        )
        runCurrent()
        reporter.close(PlaybackState(positionMs = 7_200_000L, durationMs = 7_200_000L, ended = true))
        runCurrent()

        assertEquals(
            listOf(
                "started:movie:20000000:false",
                "stopped:movie:72000000000:true",
            ),
            events.map(Event::summary),
        )
    }

    private data class Event(
        val kind: String,
        val itemId: String,
        val sessionId: String,
        val ticks: Long,
        val paused: Boolean,
    ) {
        fun summary() = "$kind:$itemId:$ticks:$paused"
    }

    private class RecordingSink(private val events: MutableList<Event>) : PlaybackEventSink {
        override suspend fun started(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) {
            events += Event("started", itemId, sessionId, positionTicks, isPaused)
        }

        override suspend fun progress(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) {
            events += Event("progress", itemId, sessionId, positionTicks, isPaused)
        }

        override suspend fun stopped(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) {
            events += Event("stopped", itemId, sessionId, positionTicks, isPaused)
        }
    }
}
