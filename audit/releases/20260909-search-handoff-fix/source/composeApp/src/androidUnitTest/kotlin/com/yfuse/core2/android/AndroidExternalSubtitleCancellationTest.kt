package com.yfuse.core2.android

import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YSubtitleTimeBase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidExternalSubtitleCancellationTest {
    @Test
    fun reset_cancels_two_real_header_waits_and_releases_both_subtitle_permits() =
        assertResetCancelsSlowRequests { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }

    @Test
    fun reset_closes_incomplete_http_bodies_before_loading_the_next_item() =
        assertResetCancelsSlowRequests {
            // Keep the connection open after one byte; the reader waits for the advertised body.
            MockResponse().setBody("1").setHeader("Content-Length", 100_000)
        }

    private fun assertResetCancelsSlowRequests(slowResponse: () -> MockResponse) =
        runBlocking {
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path.orEmpty().contains("old")) {
                            slowResponse()
                        } else {
                            MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nNew episode\n")
                        }
                }
            server.start()
            val loader = AndroidExternalSubtitleLoader { error("HTTP must not open a content provider") }
            val completed = Channel<AndroidExternalSubtitleSession.Completion>(Channel.UNLIMITED)
            val session =
                AndroidExternalSubtitleSession(
                    scope = this,
                    load = { source, headers, id -> loader.load(source, headers, id) },
                    completed = { completed.trySend(it) },
                )
            try {
                session.reset(
                    listOf("old-primary.srt", "old-secondary.srt").map { path ->
                        YExternalSubtitleSource(server.url(path).toString())
                    },
                    emptyMap(),
                )
                session.request(externalSubtitleTrackId(0))
                session.request(externalSubtitleTrackId(1))
                repeat(2) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
                session.reset(listOf(YExternalSubtitleSource(server.url("next.srt").toString())), emptyMap())
                session.request(session.defaultId)
                // Much shorter than the real loader's 12-second HTTP timeout. Both cancelled
                // reads must finish and free permits, rather than simply dropping stale results.
                val result = withTimeout(2_000L) { completed.receive() }
                assertTrue(session.accept(result))
                assertEquals(
                    "New episode",
                    (assertNotNull(result.subtitle).cues.single().payload as YSubtitlePayload.Text).plainText,
                )
                assertEquals(
                    YSubtitleTimeBase.Presentation,
                    result.subtitle
                        ?.cues
                        ?.single()
                        ?.timeBase,
                )
                assertTrue(completed.tryReceive().isFailure, "Cancelled items must not publish completions")
            } finally {
                session.close()
                completed.close()
                server.shutdown()
            }
        }
}
