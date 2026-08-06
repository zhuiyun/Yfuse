package com.yfuse.feature.player

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * The reports have to name the session the stream URLs were built with. When they did not,
     * `Playing/Stopped` described a session the server had no encoding for, so the transcode
     * it was reporting the end of kept running.
     */
    @Test
    fun reports_carry_the_session_id_the_stream_urls_were_built_with() = runTest {
        val events = mutableListOf<Event>()
        val reporter = PlaybackProgressReporter(
            items = listOf(
                PlayerMediaItem(
                    id = "e1",
                    url = "direct-1?PlaySessionId=yfuse-abc",
                    transcodeUrl = "transcode-1?PlaySessionId=yfuse-abc",
                    title = "第一集",
                    playSessionId = "yfuse-abc",
                ),
            ),
            sink = RecordingSink(events),
            scope = this,
        )

        reporter.update(PlaybackState(playing = true, positionMs = 1_000L))
        runCurrent()
        reporter.close(PlaybackState(positionMs = 5_000L))
        runCurrent()

        assertEquals(listOf("started", "progress", "stopped"), events.map(Event::kind))
        assertEquals(List(3) { "yfuse-abc" }, events.map(Event::sessionId))
    }

    @Test
    fun an_entry_without_a_session_id_still_gets_one() = runTest {
        // Offline files, and queues marshalled by a build that predates the field.
        val events = mutableListOf<Event>()
        val reporter = PlaybackProgressReporter(
            items = listOf(PlayerMediaItem("e1", "file:///movie.mkv", "", "本地文件")),
            sink = RecordingSink(events),
            scope = this,
        )

        reporter.update(PlaybackState(playing = true, positionMs = 1_000L))
        runCurrent()
        reporter.close(PlaybackState(positionMs = 1_000L))
        runCurrent()

        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.sessionId.isNotBlank() }, events.toString())
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
