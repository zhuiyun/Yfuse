package com.yfuse.feature.player

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class PreviewHttpSourceTest {
    @Test
    fun rangeValidationRejectsShiftedAndUnboundedResponses() {
        assertEquals(Triple(128L, 255L, 1024L), parsePreviewContentRange("bytes 128-255/1024", 128))
        assertNull(parsePreviewContentRange("bytes 0-255/1024", 128))
        assertNull(parsePreviewContentRange("bytes 0-999999/1000000", 0))
        assertNull(parsePreviewContentRange("bytes 0-10/*", 0))
    }

    @Test
    fun readsTheRequestedRangeAndStopsAfterCancellation() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/4").setBody("abcd"),
            )
            val source = PreviewHttpSource(server.url("/video").toString(), emptyMap(), AtomicBoolean())
            val bytes = ByteArray(4)
            assertEquals(4, source.readAt(0, bytes, 0, 4))
            assertEquals("abcd", bytes.decodeToString())
            assertEquals(-1, source.readAt(4, bytes, 0, 4))
            source.close()
            assertFailsWith<IOException> { source.readAt(0, bytes, 0, 1) }
        }
    }

    @Test
    fun crossOriginRedirectDoesNotForwardProviderHeaders() {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/video")))
                target.enqueue(
                    MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/4").setBody("abcd"),
                )
                PreviewHttpSource(
                    origin.url("/video").toString(),
                    mapOf(
                        "Authorization" to "Bearer private",
                        "X-Provider" to "private",
                    ),
                    AtomicBoolean(),
                ).use {
                    assertEquals(4, it.readAt(0, ByteArray(4), 0, 4))
                }
                assertEquals("Bearer private", origin.takeRequest().getHeader("Authorization"))
                val redirected = target.takeRequest()
                assertNull(redirected.getHeader("Authorization"))
                assertNull(redirected.getHeader("X-Provider"))
            }
        }
    }

    @Test
    fun shortPartialResponsesDoNotTurnTheUnfetchedSuffixIntoEof() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/8").setBody("abcd"),
            )
            server.enqueue(
                MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 4-7/8").setBody("efgh"),
            )
            PreviewHttpSource(server.url("/video").toString(), emptyMap(), AtomicBoolean()).use { source ->
                val bytes = ByteArray(8)
                assertEquals(4, source.readAt(0, bytes, 0, 8))
                assertEquals(4, source.readAt(4, bytes, 4, 4))
                assertEquals("abcdefgh", bytes.decodeToString())
                assertEquals(2, source.readAt(2, bytes, 0, 2))
                assertEquals(2, server.requestCount, "Reading retained bytes must not issue another request")
                assertEquals(-1, source.readAt(8, bytes, 0, 1))
            }
            assertEquals("bytes=0-131071", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=4-131075", server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun cancellationWhileCreatingTheCallPreventsNetworkExecution() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/4").setBody("abcd"),
            )
            lateinit var source: PreviewHttpSource
            val client = OkHttpClient()
            source =
                PreviewHttpSource(
                    server.url("/video").toString(),
                    emptyMap(),
                    AtomicBoolean(),
                    Call.Factory { request ->
                        source.close()
                        client.newCall(request)
                    },
                )
            source.use {
                assertFailsWith<IOException> { it.readAt(0, ByteArray(4), 0, 4) }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test
    fun absoluteBudgetCancelsAStalledRequestEvenWhenTheClientHasNoTimeout() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val worker = Executors.newSingleThreadExecutor()
            val client =
                OkHttpClient
                    .Builder()
                    .readTimeout(
                        0,
                        TimeUnit.MILLISECONDS,
                    ).callTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            PreviewHttpSource(
                server.url("/video").toString(),
                emptyMap(),
                AtomicBoolean(),
                client,
                budgetMs = 300,
            ).use { source ->
                try {
                    val read = worker.submit<Int> { source.readAt(0, ByteArray(4), 0, 4) }
                    val failure = assertFailsWith<ExecutionException> { read.get(1500, TimeUnit.MILLISECONDS) }
                    assertIs<IOException>(failure.cause)
                } finally {
                    source.close()
                    worker.shutdownNow()
                }
            }
        }
    }

    @Test
    fun shrinkingRepresentationIsNotReportedAsOrdinaryEof() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/20").setBody("abcd"),
            )
            server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */4"))
            PreviewHttpSource(server.url("/video").toString(), emptyMap(), AtomicBoolean()).use { source ->
                assertEquals(4, source.readAt(0, ByteArray(4), 0, 4))
                assertFailsWith<IOException> { source.readAt(10, ByteArray(4), 0, 4) }
            }
        }
    }

    @Test
    fun changedWeakEntityTagIsRejectedWithoutSendingItAsIfRange() {
        MockWebServer().use { server ->
            server.enqueue(partial(0, "abcd", "W/\"a\""))
            server.enqueue(partial(4, "EFGH", "W/\"b\""))
            PreviewHttpSource(server.url("/video").toString(), emptyMap(), AtomicBoolean()).use { source ->
                assertEquals(4, source.readAt(0, ByteArray(4), 0, 4))
                assertFailsWith<IOException> { source.readAt(4, ByteArray(4), 0, 4) }
            }
            assertNull(server.takeRequest().getHeader("If-Range"))
            assertNull(server.takeRequest().getHeader("If-Range"))
        }
    }

    @Test
    fun appearingDisappearingOrChangingValidatorStrengthRejectsMixedBlocks() {
        val changes =
            listOf<Pair<String?, String?>>(
                null to "\"a\"",
                "\"a\"" to null,
                "W/\"a\"" to "\"a\"",
                "\"a\"" to "W/\"a\"",
            )
        for ((first, second) in changes) {
            MockWebServer().use { server ->
                server.enqueue(partial(0, "abcd", first))
                server.enqueue(partial(4, "EFGH", second))
                PreviewHttpSource(server.url("/video").toString(), emptyMap(), AtomicBoolean()).use { source ->
                    assertEquals(4, source.readAt(0, ByteArray(4), 0, 4))
                    assertFailsWith<IOException> { source.readAt(4, ByteArray(4), 0, 4) }
                }
            }
        }
    }

    @Test
    fun changedFinalResourceIsRejectedEvenWhenItsLengthAndStrongTagMatch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/first"))
            server.enqueue(partial(0, "abcd", "\"same\""))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/other"))
            server.enqueue(partial(4, "EFGH", "\"same\""))
            PreviewHttpSource(server.url("/redirect").toString(), emptyMap(), AtomicBoolean()).use { source ->
                assertEquals(4, source.readAt(0, ByteArray(4), 0, 4))
                assertFailsWith<IOException> { source.readAt(4, ByteArray(4), 0, 4) }
            }
            repeat(4) { assertNull(server.takeRequest().getHeader("If-Range")) }
        }
    }

    @Test
    fun strongIfRangeIsBoundToTheUnchangedFinalResource() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/fixed"))
            server.enqueue(partial(0, "abcd", "\"stable\""))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/fixed"))
            server.enqueue(partial(4, "efgh", "\"stable\""))
            PreviewHttpSource(
                server.url("/redirect").toString(),
                mapOf("If-Range" to "W/\"caller\""),
                AtomicBoolean(),
            ).use { source ->
                assertEquals(4, source.readAt(0, ByteArray(4), 0, 4))
                assertEquals(4, source.readAt(4, ByteArray(4), 0, 4))
            }
            repeat(3) { assertNull(server.takeRequest().getHeader("If-Range")) }
            assertEquals("\"stable\"", server.takeRequest().getHeader("If-Range"))
        }
    }

    @Test
    fun unchangedWeakTagsRemainBestEffortButNeverBecomeIfRange() {
        MockWebServer().use { server ->
            server.enqueue(partial(0, "abcd", "W/\"same\""))
            server.enqueue(partial(4, "efgh", "W/\"same\""))
            PreviewHttpSource(server.url("/video").toString(), emptyMap(), AtomicBoolean()).use { source ->
                assertEquals(4, source.readAt(0, ByteArray(4), 0, 4))
                assertEquals(4, source.readAt(4, ByteArray(4), 0, 4))
            }
            repeat(2) { assertNull(server.takeRequest().getHeader("If-Range")) }
        }
    }

    @Test
    fun malformedUnsatisfiedRangesAreNotOrdinaryEof() {
        MockWebServer().use { server ->
            for (header in listOf(
                "bytes */-1",
                "prefix bytes */4",
                "bytes */4 suffix",
                "bytes */+4",
                "bytes */9223372036854775808",
            )) {
                server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", header))
                PreviewHttpSource(server.url("/video").toString(), emptyMap(), AtomicBoolean()).use { source ->
                    assertFailsWith<IOException> { source.readAt(10, ByteArray(4), 0, 4) }
                }
            }
        }
    }

    @Test
    fun validZeroLengthUnsatisfiedRangeRemainsEof() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */0"))
            PreviewHttpSource(server.url("/empty").toString(), emptyMap(), AtomicBoolean()).use { source ->
                assertEquals(-1, source.readAt(0, ByteArray(1), 0, 1))
                assertEquals(0L, source.getSize())
            }
        }
    }

    private fun partial(
        start: Long,
        body: String,
        tag: String?,
    ): MockResponse =
        MockResponse()
            .setResponseCode(206)
            .addHeader("Content-Range", "bytes $start-${start + body.length - 1}/8")
            .setBody(body)
            .apply { if (tag != null) addHeader("ETag", tag) }
}
