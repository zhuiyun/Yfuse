package com.yfuse.feature.player

import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEventSinkTest {
    private val server = SavedServer("server", "https://emby.example", "Emby", "user", "User", "token")
    private val requests = mutableListOf<String>()
    private val sink =
        EmbyPlaybackEventSink(
            testRepo { request ->
                requests += "${request.method.value} ${request.url.encodedPath}"
                json("{}")
            },
            server,
        )

    @Test
    fun a_direct_play_stop_sends_no_transcoder_cleanup() =
        runTest {
            sink.stoppedWithMethod("item", "session", 10L, false, "DirectPlay")
            // Without a method the stop is reported as the default direct play.
            sink.stopped("item", "session", 10L, false)

            assertEquals(List(2) { "POST /Sessions/Playing/Stopped" }, requests)
        }

    @Test
    fun a_transcode_or_direct_stream_stop_ends_the_servers_encoder() =
        runTest {
            sink.stoppedWithMethod("item", "session", 10L, false, "Transcode")
            sink.stoppedWithMethod("item", "session", 10L, false, "DirectStream")

            assertEquals(2, requests.count { it == "DELETE /Videos/ActiveEncodings" })
        }

    @Test
    fun an_unnamed_session_never_reaches_the_encoder_endpoint() =
        runTest {
            sink.stoppedWithMethod("item", "", 10L, false, "Transcode")

            assertTrue(sink.stopEncoding(""))
            assertEquals(listOf("POST /Sessions/Playing/Stopped"), requests)
        }

    @Test
    fun only_transcodes_and_direct_streams_start_an_encoder() {
        assertTrue("Transcode".startsServerEncoder())
        assertTrue("directstream".startsServerEncoder())
        assertFalse("DirectPlay".startsServerEncoder())
        assertFalse("".startsServerEncoder())
    }
}
