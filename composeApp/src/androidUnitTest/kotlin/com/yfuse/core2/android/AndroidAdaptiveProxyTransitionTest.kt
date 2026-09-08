package com.yfuse.core2.android

import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real loopback requests with deterministic fake upstream bytes; these tests do not assert decoder output. */
class AndroidAdaptiveProxyTransitionTest {
    @Test
    fun slow_alternate_does_not_delay_selected_playlist_and_different_init_requires_a_new_target() {
        val upstream = FixtureUpstream(hlsResources(), blockedPath = "/low.m3u8")
        withProxy(upstream) { proxy ->
            val root =
                proxy.localUrl(
                    "https://media.example.test/master.m3u8",
                    cacheable = false,
                    cacheIdentity = YCacheIdentity("test", "hls"),
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val opening =
                    executor.submit<YAdaptivePlaybackTarget?> { runBlocking { proxy.resolvePlaybackTarget(root, 0L) } }
                val first = assertNotNull(opening.get(2L, TimeUnit.SECONDS))
                assertTrue(upstream.blockedOpen.await(2L, TimeUnit.SECONDS))
                val manifest = readUrl(first.uri).decodeToString()
                assertEquals("HIGH_INIT", readUrl(hlsInitialization(manifest)).decodeToString())
                val segment = manifest.lineSequence().first { it.isNotBlank() && !it.startsWith('#') }
                assertNull(proxy.pollPlaybackTransition(root, 3_000L))

                upstream.unblock.countDown()
                assertTrue(upstream.alternateRead.await(2L, TimeUnit.SECONDS))
                proxy.updatePlaybackFeedback(YAdaptivePlaybackFeedback(0L, true, 1f, 4L))
                var switched: YAdaptivePlaybackTarget? = null
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
                while (switched == null && System.nanoTime() < deadline) {
                    // The first completed request supplies a real slow upstream sample to the selector.
                    assertEquals("HIGH_SEGMENT", readUrl(segment).decodeToString())
                    switched = proxy.pollPlaybackTransition(root, 3_000L)
                }
                val next = assertNotNull(switched)
                assertEquals(3_000L, next.localPositionMs)
                assertEquals(0L, next.presentationOffsetMs)
                assertEquals(4L, next.feedbackGeneration)
                assertTrue(next.revision > first.revision)
                assertNotEquals(first.uri, next.uri)
                assertNull(proxy.pollPlaybackTransition(root, 3_000L))
                assertEquals(
                    "LOW_INIT",
                    readUrl(hlsInitialization(readUrl(next.uri).decodeToString())).decodeToString(),
                )
                // The old decoder's manifest remains internally coherent until the actor releases it.
                assertEquals(
                    "HIGH_INIT",
                    readUrl(hlsInitialization(readUrl(first.uri).decodeToString())).decodeToString(),
                )
            } finally {
                upstream.unblock.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun cancelling_manifest_resolution_closes_the_inflight_transport_without_publishing_a_target() {
        val upstream = FixtureUpstream(hlsResources(), blockedPath = "/master.m3u8")
        withProxy(upstream) { proxy ->
            val root =
                proxy.localUrl(
                    "https://media.example.test/master.m3u8",
                    cacheable = false,
                    cacheIdentity = null,
                )
            runBlocking {
                val opening = async(Dispatchers.Default) { proxy.resolvePlaybackTarget(root, 0L) }
                assertTrue(upstream.blockedOpen.await(2L, TimeUnit.SECONDS))
                withTimeout(2_000L) { opening.cancelAndJoin() }
                assertTrue(upstream.closedTransports.get() > 0)
                assertNull(proxy.pollPlaybackTransition(root, 0L))
                assertNotNull(proxy.resolvePlaybackTarget(root, 0L))
                assertEquals(2, upstream.requests.count { URI(it.uri).path == "/master.m3u8" })
            }
        }
    }

    @Test
    fun an_old_period_abr_plan_is_discarded_at_the_boundary_before_resolving_the_next_period() {
        val nextPeriod =
            """
            <Period id="next" start="PT10S" duration="PT10S">
              <AdaptationSet contentType="video" mimeType="video/mp4" codecs="avc1.640028">
                <Representation id="next-only" bandwidth="2000000">
                  <SegmentTemplate duration="1" initialization="next-init.mp4" media="next-${'$'}Number${'$'}.m4s"/>
                </Representation>
              </AdaptationSet>
            </Period>
            """.trimIndent()
        val manifest =
            dashLadder()
                .replace("mediaPresentationDuration=\"PT10S\"", "mediaPresentationDuration=\"PT20S\"")
                .replace("</MPD>", "$nextPeriod</MPD>")
        val upstream = FixtureUpstream(mapOf("/movie.mpd" to manifest, "/high-1.m4s" to "HIGH_SEGMENT"))
        withProxy(upstream) { proxy ->
            val root = proxy.localUrl("https://media.example.test/movie.mpd", cacheable = false, cacheIdentity = null)
            val first = assertNotNull(runBlocking { proxy.resolvePlaybackTarget(root, 0L) })
            val segment =
                assertNotNull(Regex("media=\"([^\"]+)\"").find(readUrl(first.uri).decodeToString()))
                    .groupValues[1]
                    .replace("${'$'}Number${'$'}", "1")
            proxy.updatePlaybackFeedback(YAdaptivePlaybackFeedback(0L, true, 1f, 2L))
            readUrl(segment)
            readUrl(segment)
            // "low" belongs only to Period 1; applying it to Period 2 used to throw inside the player collector.
            assertNull(proxy.pollPlaybackTransition(root, 10_000L))
            val next = assertNotNull(runBlocking { proxy.resolvePlaybackTarget(root, 10_000L) })
            assertEquals(10_000L, next.presentationOffsetMs)
            assertEquals(0L, next.localPositionMs)
            assertTrue("id=\"next-only\"" in readUrl(next.uri).decodeToString())
            readUrl(segment)
            assertNull(proxy.pollPlaybackTransition(root, 10_000L))
        }
    }

    @Test
    fun dash_switch_changes_initialization_only_after_the_actor_consumes_a_reopen_plan() {
        val upstream =
            FixtureUpstream(
                mapOf(
                    "/movie.mpd" to dashLadder(),
                    "/high-init.mp4" to "HIGH_INIT",
                    "/low-init.mp4" to "LOW_INIT",
                    "/high-1.m4s" to "HIGH_SEGMENT",
                    "/low-1.m4s" to "LOW_SEGMENT",
                ),
            )
        withProxy(upstream) { proxy ->
            val root =
                proxy.localUrl("https://media.example.test/movie.mpd", cacheable = false, cacheIdentity = null)
            val first = assertNotNull(runBlocking { proxy.resolvePlaybackTarget(root, 0L) })
            val manifest = readUrl(first.uri).decodeToString()
            assertEquals("HIGH_INIT", readUrl(dashInitialization(manifest)).decodeToString())
            val segment =
                assertNotNull(Regex("media=\"([^\"]+)\"").find(manifest))
                    .groupValues[1]
                    .replace("${'$'}Number${'$'}", "1")
            proxy.updatePlaybackFeedback(YAdaptivePlaybackFeedback(0L, true, 1f, 2L))
            assertEquals("HIGH_SEGMENT", readUrl(segment).decodeToString())
            assertEquals("HIGH_SEGMENT", readUrl(segment).decodeToString())
            val next = assertNotNull(proxy.pollPlaybackTransition(root, 1_000L))
            assertEquals(1_000L, next.localPositionMs)
            assertEquals("LOW_INIT", readUrl(dashInitialization(readUrl(next.uri).decodeToString())).decodeToString())
            assertNull(proxy.pollPlaybackTransition(root, 1_000L))
        }
    }

    @Test
    fun dash_period_targets_keep_global_time_and_upstream_identity_while_rebuilding_initialization() =
        runBlocking {
            val upstream =
                FixtureUpstream(
                    mapOf(
                        "/movie.mpd" to dashPresentation(),
                        "/first/init.mp4" to "FIRST_INIT",
                        "/second/init.mp4" to "SECOND_INIT",
                    ),
                )
            withProxy(upstream) { proxy ->
                val credentials = YTransportCredentials.UsernamePassword("fixture", "fixture-only")
                val root =
                    proxy.localUrl(
                        "https://media.example.test/movie.mpd",
                        upstreamHeaders = mapOf("X-Fixture-Identity" to "account-A"),
                        credentials = credentials,
                        cacheable = false,
                        cacheIdentity = YCacheIdentity("test-account-A", "dash"),
                    )
                val second = assertNotNull(runBlocking { proxy.resolvePlaybackTarget(root, 6_000L) })
                assertEquals(5_000L, second.presentationOffsetMs)
                assertEquals(1_000L, second.localPositionMs)
                assertEquals(10_000L, second.presentationDurationMs)
                assertEquals(10_000L, second.periodEndGlobalMs)
                val secondManifest = readUrl(second.uri).decodeToString()
                assertTrue(secondManifest.contains("presentationTimeOffset=\"5000\""))
                assertTrue(secondManifest.contains("mediaPresentationDuration=\"PT5S\""))
                assertEquals("SECOND_INIT", readUrl(dashInitialization(secondManifest)).decodeToString())

                val first = assertNotNull(runBlocking { proxy.resolvePlaybackTarget(root, 1_000L) })
                assertEquals(0L, first.presentationOffsetMs)
                assertEquals(1_000L, first.localPositionMs)
                assertEquals(5_000L, first.periodEndGlobalMs)
                assertNotEquals(second.uri, first.uri)
                assertEquals(
                    "FIRST_INIT",
                    readUrl(dashInitialization(readUrl(first.uri).decodeToString())).decodeToString(),
                )
                assertTrue(upstream.requests.all { it.headers["X-Fixture-Identity"] == "account-A" })
                assertTrue(upstream.requests.all { it.credentials === credentials })
                assertEquals(1, upstream.requests.count { URI(it.uri).path == "/movie.mpd" })
            }
        }

    private fun withProxy(
        upstream: FixtureUpstream,
        block: (AndroidYCoreHttpProxy) -> Unit,
    ) {
        val directory = Files.createTempDirectory("ycore-adaptive-proxy-test").toFile()
        try {
            AndroidYCoreHttpProxy(
                userAgent = "YCore-fixture",
                cacheMaximumBytes = 0L,
                createTransport = upstream::transport,
                isMeteredNetwork = { false },
                cacheDirectory = directory,
            ).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun readUrl(uri: String): ByteArray {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun hlsInitialization(manifest: String): String =
        assertNotNull(Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(manifest)).groupValues[1]

    private fun dashInitialization(manifest: String): String =
        assertNotNull(Regex("initialization=\"([^\"]+)\"").find(manifest)).groupValues[1]

    private class FixtureUpstream(
        private val resources: Map<String, String>,
        private val blockedPath: String? = null,
    ) {
        val blockedOpen = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val alternateRead = CountDownLatch(1)
        val closedTransports = AtomicInteger()
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()

        fun transport(): YMediaTransport =
            object : YMediaTransport {
                override val supportedProtocols = setOf(YSourceProtocol.Https)
                override val features = emptySet<YTransportFeature>()
                private var path = ""
                private var bytes = byteArrayOf()
                private var position = 0

                override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                    requests += request
                    path = URI(request.uri).path
                    if (path == blockedPath) {
                        blockedOpen.countDown()
                        check(unblock.await(3L, TimeUnit.SECONDS)) { "Test did not release alternate manifest" }
                    }
                    bytes = requireNotNull(resources[path]) { "Unexpected fixture request $path" }.encodeToByteArray()
                    return YMediaTransportResponse(200, contentLength = bytes.size.toLong())
                }

                override suspend fun read(
                    destination: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (position == bytes.size) {
                        if (path == blockedPath) alternateRead.countDown()
                        return -1
                    }
                    if (path.endsWith(".m4s")) Thread.sleep(25L)
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(destination, offset, position, position + count)
                    position += count
                    return count
                }

                override suspend fun close() {
                    closedTransports.incrementAndGet()
                    if (path == blockedPath) unblock.countDown()
                }
            }
    }

    private fun hlsResources(): Map<String, String> =
        mapOf(
            "/master.m3u8" to
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="avc1.640028",RESOLUTION=640x360
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="avc1.640028",RESOLUTION=1280x720
                high.m3u8
                """.trimIndent(),
            "/high.m3u8" to hlsMedia("high"),
            "/low.m3u8" to hlsMedia("low"),
            "/high-init.mp4" to "HIGH_INIT",
            "/low-init.mp4" to "LOW_INIT",
            "/high-1.m4s" to "HIGH_SEGMENT",
            "/low-1.m4s" to "LOW_SEGMENT",
        )

    private fun hlsMedia(variant: String): String =
        """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:2
        #EXT-X-MAP:URI="$variant-init.mp4"
        #EXTINF:2,
        $variant-1.m4s
        #EXTINF:2,
        $variant-2.m4s
        #EXT-X-ENDLIST
        """.trimIndent()

    private fun dashPresentation(): String =
        """
        <MPD type="static" mediaPresentationDuration="PT10S">
          <Period id="first" duration="PT5S">
            <BaseURL>first/</BaseURL>
            <AdaptationSet contentType="video" mimeType="video/mp4" codecs="avc1.640028">
              <SegmentTemplate timescale="1000" duration="1000"
                  initialization="init.mp4" media="${'$'}Number${'$'}.m4s"/>
              <Representation id="video" bandwidth="1000000"/>
            </AdaptationSet>
          </Period>
          <Period id="second" duration="PT5S">
            <BaseURL>second/</BaseURL>
            <AdaptationSet contentType="video" mimeType="video/mp4" codecs="avc1.640028">
              <SegmentTemplate timescale="1000" duration="1000" presentationTimeOffset="5000"
                  initialization="init.mp4" media="${'$'}Number${'$'}.m4s"/>
              <Representation id="video" bandwidth="1000000"/>
            </AdaptationSet>
          </Period>
        </MPD>
        """.trimIndent()

    private fun dashLadder(): String =
        """
        <MPD type="static" mediaPresentationDuration="PT10S">
          <Period duration="PT10S">
            <AdaptationSet contentType="video" mimeType="video/mp4" codecs="avc1.640028">
              <Representation id="low" bandwidth="1000000">
                <SegmentTemplate duration="1" initialization="low-init.mp4" media="low-${'$'}Number${'$'}.m4s"/>
              </Representation>
              <Representation id="high" bandwidth="4000000">
                <SegmentTemplate duration="1" initialization="high-init.mp4" media="high-${'$'}Number${'$'}.m4s"/>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
        """.trimIndent()
}
