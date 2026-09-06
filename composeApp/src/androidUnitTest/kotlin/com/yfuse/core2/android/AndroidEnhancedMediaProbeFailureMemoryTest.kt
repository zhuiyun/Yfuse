package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidEnhancedMediaProbeFailureMemoryTest {
    private val item =
        YMediaItem(
            id = "hidden-audio",
            uri = "https://media.example.test/direct-stream/opaque-id",
        )

    @Test
    fun failedDeepProbeIsNotRepeatedInsideTheRetryWindow() {
        var now = 0L
        var opens = 0
        val probe =
            AndroidEnhancedMediaProbe(
                clock = { now },
                probeSource = {
                    opens++
                    YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable)
                },
            )

        assertIs<YCore2ProbeResult.Failure>(probe.probe(item))
        now += 5_000_000_000L
        assertIs<YCore2ProbeResult.Failure>(probe.probe(item))
        assertEquals(1, opens)

        now += 30_000_000_000L
        assertIs<YCore2ProbeResult.Failure>(probe.probe(item))
        assertEquals(2, opens)
    }

    @Test
    fun laterSuccessReplacesTheRememberedFailure() {
        var now = 0L
        var fail = true
        var opens = 0
        val probe =
            AndroidEnhancedMediaProbe(
                clock = { now },
                probeSource = {
                    opens++
                    if (fail) YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable) else success()
                },
            )

        assertIs<YCore2ProbeResult.Failure>(probe.probe(item))
        fail = false
        now += 31_000_000_000L
        assertIs<YCore2ProbeResult.Success>(probe.probe(item))
        assertIs<YCore2ProbeResult.Success>(probe.probe(item))
        assertEquals(2, opens)
    }

    @Test
    fun unavailableBridgeIsNeverRemembered() {
        var opens = 0
        val probe =
            AndroidEnhancedMediaProbe(
                clock = { 0L },
                probeSource = {
                    opens++
                    null
                },
            )

        assertEquals(null, probe.probe(item))
        assertEquals(null, probe.probe(item))
        assertEquals(2, opens)
    }

    @Test
    fun refreshedUrlOrAuthorizationIsProbedImmediatelyForTheSameMediaIdentity() {
        val original = item.copy(cacheIdentity = YCacheIdentity("account", "movie"))
        val refreshed = listOf(
            original.copy(uri = "https://media.example.test/refreshed-stream"),
            original.copy(headers = mapOf("Authorization" to "test-refreshed-authorization")),
            original.copy(transportCredentials = YTransportCredentials.UsernamePassword("test-user", "new-password")),
        )
        refreshed.forEach { next ->
            var opens = 0
            val probe = AndroidEnhancedMediaProbe(
                clock = { 0L },
                probeSource = {
                    opens++
                    if (opens == 1) YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable) else success()
                },
            )

            assertIs<YCore2ProbeResult.Failure>(probe.probe(original))
            assertIs<YCore2ProbeResult.Success>(probe.probe(next))
            assertEquals(2, opens)
        }
    }

    private fun success(): YCore2ProbeResult.Success =
        YCore2ProbeResult.Success(
            playbackRequest =
                YPlaybackRequest(
                    container = YContainer.Matroska,
                    video =
                        YVideoRequirement(
                            codec = YVideoCodec.H265,
                            width = 3840,
                            height = 2160,
                            frameRate = 24f,
                            bitDepth = 10,
                            hdrType = YHdrType.Sdr,
                        ),
                    audio = null,
                    platformDemuxSupported = false,
                    enhancedDemuxSupported = true,
                    preferTunnel = false,
                ),
            videoMime = "video/hevc",
            audioMime = null,
            durationMs = 60_000L,
        )
}
