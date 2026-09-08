package com.yfuse.core2.android

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import com.yfuse.core2.quirk.InMemoryYCore2FailureStore
import com.yfuse.core2.quirk.YCore2FailureLedger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Only device-generated pixels and loopback sockets; no user files, server accounts, or external network. */
@RunWith(AndroidJUnit4::class)
class AndroidAdaptiveDashInstrumentedTest {
    @Test(timeout = 180_000L)
    fun generated_fragmented_dash_seeks_across_periods_and_automatically_advances_on_ended() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            GeneratedDashTestMedia.create(context.cacheDir).use { firstFixture ->
                GeneratedDashTestMedia.create(context.cacheDir, 160, 90).use { secondFixture ->
                    val origin = GeneratedDashOrigin(firstFixture, secondFixture)
                    AndroidYCoreHttpProxy(
                        context = context,
                        userAgent = "YCore-generated-DASH-test",
                        cacheMaximumBytes = 0L,
                        createTransport = origin::transport,
                        isMeteredNetwork = { false },
                    ).use { proxy ->
                        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                            val output = surface(scenario)
                            val uri =
                                proxy.localUrl(
                                    upstreamUri = "https://generated.invalid/presentation.mpd",
                                    cacheable = false,
                                    cacheIdentity = YCacheIdentity("generated-fixture", "two-period-dash"),
                                )
                            val player =
                                AndroidAdaptiveCore2YPlayer(
                                    context = context,
                                    request =
                                        YPlayerOpenRequest(
                                            items = listOf(YMediaItem("generated-dash", uri)),
                                            autoPlay = true,
                                            autoNext = false,
                                        ),
                                    allowAudioPassthrough = false,
                                    adaptiveFeedbackSink = proxy,
                                    failureLedger =
                                        YCore2FailureLedger(InMemoryYCore2FailureStore(), System::currentTimeMillis),
                                )
                            try {
                                withTimeout(90_000L) {
                                    assertTrue(player.setVideoOutput(output))
                                    player.prepare()
                                    player.play()
                                    awaitVideo(player, "first-period") { it.positionMs < 10_000L }
                                    assertDimensions(player, firstFixture)
                                    assertEquals(20_000L, player.state.value.durationMs)
                                    assertTrue(origin.opens("/first/init.mp4") > 0)

                                    freshVideo(player, "global-seek-second", 12_300L)
                                    assertDimensions(player, secondFixture)
                                    assertTrue(origin.opens("/second/init.mp4") > 0)
                                    assertEquals(20_000L, player.state.value.durationMs)

                                    freshVideo(player, "global-seek-first", 8_800L)
                                    assertDimensions(player, firstFixture)
                                    val secondBefore = origin.opens("/second/init.mp4")
                                    awaitVideo(player, "automatic-period-transition") { state ->
                                        state.positionMs in 10_000L..15_000L &&
                                            origin.opens("/second/init.mp4") > secondBefore
                                    }
                                    assertEquals(0, player.state.value.currentIndex)
                                    assertDimensions(player, secondFixture)
                                    assertEquals(1, player.state.value.itemCount)
                                    assertEquals(20_000L, player.state.value.durationMs)

                                    freshVideo(player, "global-seek-final", 19_000L)
                                    val ended =
                                        withTimeout(20_000L) {
                                            player.state.first { state ->
                                                assertHealthy("final-ended", state)
                                                state.phase == YPlaybackPhase.Ended
                                            }
                                        }
                                    assertTrue(
                                        "Presentation ended before the second Period: $ended",
                                        ended.positionMs >= 19_900L,
                                    )
                                    assertEquals(20_000L, ended.durationMs)
                                    progress("verified two Periods, global seeks, automatic transition, final Ended")
                                }
                            } finally {
                                runCatching { progress("final state=${player.state.value}") }
                                runCatching {
                                    progress("generated DASH origin requests=${origin.requestDiagnostics()}")
                                }
                                // Release codecs before ActivityScenario destroys its holder-owned Surface.
                                player.release()
                            }
                        }
                    }
                }
            }
        }

    private fun assertDimensions(
        player: YPlayer,
        fixture: GeneratedDashTestMedia,
    ) {
        assertEquals(fixture.width, player.state.value.diagnostics.videoWidth)
        assertEquals(fixture.height, player.state.value.diagnostics.videoHeight)
    }

    private suspend fun surface(scenario: ActivityScenario<MainActivity>): AndroidSurfaceVideoOutput {
        val ready = CompletableDeferred<AndroidSurfaceVideoOutput>()
        scenario.onActivity { activity ->
            val view = SurfaceView(activity)
            view.holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (holder.surface.isValid) ready.complete(AndroidSurfaceVideoOutput(holder.surface))
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (!ready.isCompleted) {
                            ready.completeExceptionally(
                                AssertionError("DASH test Surface disappeared"),
                            )
                        }
                    }
                },
            )
            activity.setContentView(view)
        }
        return withTimeout(10_000L) { ready.await() }
    }

    private suspend fun freshVideo(
        player: YPlayer,
        label: String,
        positionMs: Long,
    ) = coroutineScope {
        val invalidated =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000L) { player.state.first { !it.diagnostics.videoOutputVerified } }
            }
        player.seekTo(positionMs)
        player.play()
        invalidated.await()
        awaitVideo(player, label) { it.positionMs in (positionMs - 100L)..(positionMs + 1_200L) }
    }

    private suspend fun awaitVideo(
        player: YPlayer,
        label: String,
        predicate: (YPlayerState) -> Boolean,
    ) {
        val state =
            withTimeout(25_000L) {
                player.state.first { state ->
                    assertHealthy(label, state)
                    state.diagnostics.videoOutputVerified && predicate(state)
                }
            }
        assertTrue("$label did not use native decoding: $state", state.diagnostics.route != YPlaybackRoute.Legacy)
        progress("$label: position=${state.positionMs}, duration=${state.durationMs}, route=${state.diagnostics.route}")
    }

    private fun assertHealthy(
        label: String,
        state: YPlayerState,
    ) {
        assertFalse("$label failed: $state", state.phase == YPlaybackPhase.Failed)
    }

    private fun progress(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}

/** The real production proxy serves HTTP; only its upstream transport reads this test's fixed byte arrays. */
private class GeneratedDashOrigin(
    first: GeneratedDashTestMedia,
    second: GeneratedDashTestMedia,
) {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val requests = ConcurrentLinkedQueue<String>()
    private val requestCount = AtomicInteger()
    private val resources =
        mapOf(
            "/presentation.mpd" to manifest(first, second).encodeToByteArray(),
            "/first/init.mp4" to first.initialization.readBytes(),
            "/first/segment-1.m4s" to first.segment.readBytes(),
            "/second/init.mp4" to second.initialization.readBytes(),
            "/second/segment-1.m4s" to second.segment.readBytes(),
        )

    fun opens(path: String): Int = counts[path]?.get() ?: 0

    fun requestDiagnostics(): List<String> = requests.toList()

    fun transport(): YMediaTransport =
        object : YMediaTransport {
            override val supportedProtocols = setOf(YSourceProtocol.Https)
            override val features = setOf(YTransportFeature.ByteRange)
            private var bytes = byteArrayOf()
            private var position = 0
            private var end = 0

            override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                val path = URI(request.uri).path
                if (requestCount.incrementAndGet() <= 100) {
                    requests.add("$path range=${request.range} authored=${path in resources}")
                }
                bytes = requireNotNull(resources[path]) { "DASH fixture requested an unauthored resource: $path" }
                counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                val range = request.range
                position = range?.startInclusive?.toInt() ?: 0
                end = minOf((range?.endInclusive ?: bytes.lastIndex.toLong()) + 1L, bytes.size.toLong()).toInt()
                check(position in 0 until end)
                return YMediaTransportResponse(
                    statusCode = if (range == null) 200 else 206,
                    contentLength = bytes.size.toLong(),
                    acceptedRange = range?.let { YByteRange(position.toLong(), (end - 1).toLong()) },
                )
            }

            override suspend fun read(
                destination: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (position == end) return -1
                val count = minOf(length, end - position)
                bytes.copyInto(destination, offset, position, position + count)
                position += count
                return count
            }

            override suspend fun close() = Unit
        }

    private fun manifest(
        first: GeneratedDashTestMedia,
        second: GeneratedDashTestMedia,
    ): String =
        """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" minBufferTime="PT1.5S"
            profiles="urn:mpeg:dash:profile:isoff-live:2011" mediaPresentationDuration="PT20S">
          ${period("first", 0, first)}
          ${period("second", 10, second)}
        </MPD>
        """.trimIndent()

    private fun period(
        id: String,
        startSeconds: Int,
        fixture: GeneratedDashTestMedia,
    ): String =
        """
        <Period id="$id" start="PT${startSeconds}S" duration="PT10S">
          <BaseURL>$id/</BaseURL>
          <AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true" startWithSAP="1">
            <SegmentTemplate timescale="1000000" duration="10000000" startNumber="1"
                initialization="init.mp4" media="segment-${'$'}Number${'$'}.m4s"/>
            <Representation id="video" bandwidth="250000" codecs="${fixture.codec}"
                width="${fixture.width}" height="${fixture.height}" frameRate="10"/>
          </AdaptationSet>
        </Period>
        """.trimIndent()
}
