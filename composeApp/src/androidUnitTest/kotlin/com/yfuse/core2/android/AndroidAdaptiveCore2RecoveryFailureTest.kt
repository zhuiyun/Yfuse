package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.quirk.YCore2FailureKey
import com.yfuse.core2.recovery.YPlaybackRecoveryAction
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Recovery bookkeeping behind the 1.0.83 startup failures (incidents A and B of the review). */
class AndroidAdaptiveCore2RecoveryFailureTest {
    private val hiddenAudio =
        YPlaybackException(
            category = YPlaybackFailureCategory.Container,
            stage = YPlaybackFailureStage.Demux,
            safeDetail = "$NATIVE_DIRECT_HIDDEN_AUDIO_DETAIL (server audio codecs: eac3)",
        )

    @Test
    fun `a recovery after a spent start deadline gets an allowance of its own`() {
        assertTrue(yCoreRecoveryNeedsFreshProbeBudget(remainingStartMs = 0L))
        assertTrue(yCoreRecoveryNeedsFreshProbeBudget(remainingStartMs = 19_999L))
        // An early failure keeps the longer remainder instead of being cut to the allowance.
        assertFalse(yCoreRecoveryNeedsFreshProbeBudget(remainingStartMs = 20_000L))
        assertFalse(yCoreRecoveryNeedsFreshProbeBudget(remainingStartMs = 26_500L))
    }

    @Test
    fun `only a deadline abort counts as running out of time`() {
        assertTrue(
            yCoreAttemptRanOutOfTime(
                reported = AndroidProbeAbortedException("deadline"),
                category = YPlaybackFailureCategory.Unknown,
                attemptDeadlineStopped = true,
            ),
        )
        // The enhanced source open wraps a deadline as a Network SourceOpen failure.
        assertTrue(
            yCoreAttemptRanOutOfTime(
                reported =
                    YPlaybackException(
                        category = YPlaybackFailureCategory.Network,
                        stage = YPlaybackFailureStage.SourceOpen,
                        cause = AndroidProbeAbortedException("deadline"),
                    ),
                category = YPlaybackFailureCategory.Network,
                attemptDeadlineStopped = true,
            ),
        )
        assertFalse(
            yCoreAttemptRanOutOfTime(
                reported = hiddenAudio,
                category = YPlaybackFailureCategory.Container,
                attemptDeadlineStopped = true,
            ),
        )
        // Without a reported failure only an unclassified one after the deadline qualifies.
        assertTrue(yCoreAttemptRanOutOfTime(null, YPlaybackFailureCategory.Unknown, attemptDeadlineStopped = true))
        assertTrue(yCoreAttemptRanOutOfTime(null, null, attemptDeadlineStopped = true))
        assertFalse(yCoreAttemptRanOutOfTime(null, YPlaybackFailureCategory.Unknown, attemptDeadlineStopped = false))
        assertFalse(yCoreAttemptRanOutOfTime(null, YPlaybackFailureCategory.Decoder, attemptDeadlineStopped = true))
        assertFalse(yCoreAttemptRanOutOfTime(null, YPlaybackFailureCategory.Network, attemptDeadlineStopped = true))
    }

    @Test
    fun `a kept route failure carries the typed stage and the hidden audio message`() {
        val kept =
            assertNotNull(
                yCoreRouteFailure(
                    route = YPlaybackRoute.NativeDirect,
                    category = YPlaybackFailureCategory.Container,
                    message = null,
                    reason = null,
                    reported = hiddenAudio,
                ),
            )

        assertEquals(YPlaybackFailureCategory.Container, kept.category)
        assertEquals(YCORE_HIDDEN_AUDIO_TRACK_MESSAGE, kept.message)
        assertEquals(
            "NativeDirect failed at Demux: $NATIVE_DIRECT_HIDDEN_AUDIO_DETAIL (server audio codecs: eac3)",
            kept.reason,
        )
        assertTrue(kept.concrete)
    }

    @Test
    fun `an unreported failure keeps what the route itself published`() {
        val kept =
            assertNotNull(
                yCoreRouteFailure(
                    route = YPlaybackRoute.NativeEnhanced,
                    category = YPlaybackFailureCategory.Decoder,
                    message = "YCore 2.0 无法启动当前视频解码器",
                    reason = "NativeEnhanced failed at VideoDecoderConfigure",
                    reported = null,
                ),
            )
        assertEquals("YCore 2.0 无法启动当前视频解码器", kept.message)
        assertEquals("NativeEnhanced failed at VideoDecoderConfigure", kept.reason)

        assertNull(yCoreRouteFailure(YPlaybackRoute.NativeDirect, null, "x", null, reported = null))
        for (category in listOf(YPlaybackFailureCategory.Network, YPlaybackFailureCategory.Unknown)) {
            val failure = yCoreRouteFailure(YPlaybackRoute.NativeDirect, category, "x", null, null)
            assertFalse(assertNotNull(failure).concrete, category.name)
        }
    }

    @Test
    fun `a spent recovery publishes the concrete failure instead of a probe timeout`() {
        // Incident A: NativeDirect hid the E-AC-3 track, the enhanced recovery ran out of time.
        val kept = assertNotNull(yCoreRouteFailure(YPlaybackRoute.NativeDirect, null, null, null, hiddenAudio))
        val published =
            yCoreUnavailableError(
                routerReason = "Core2 router failed at AndroidProbeAbortedException",
                sourceFailure = null,
                keptFailure = kept,
                protectedContent = false,
                nativeOnly = true,
                probeTimedOut = true,
                currentError = null,
                currentCategory = null,
            )

        assertEquals(YCORE_HIDDEN_AUDIO_TRACK_MESSAGE, published.message)
        assertEquals(YPlaybackFailureCategory.Container, published.category)
        assertTrue(published.reason.startsWith("NativeDirect failed at Demux"))
        assertTrue(published.reason.endsWith("recovery ended: Core2 router failed at AndroidProbeAbortedException"))
    }

    @Test
    fun `the probe timeout text remains only when nothing more concrete is known`() {
        val timeout =
            yCoreUnavailableError(
                routerReason = "router",
                sourceFailure = null,
                keptFailure =
                    YCoreRouteFailure(
                        route = YPlaybackRoute.NativeTunnel,
                        category = YPlaybackFailureCategory.Network,
                        message = "network",
                        reason = "stall",
                    ),
                protectedContent = false,
                nativeOnly = true,
                probeTimedOut = true,
                currentError = null,
                currentCategory = null,
            )
        assertEquals("YCore 2.0 片源起播探测超时，请检查网络或刷新片源后重试", timeout.message)
        assertEquals(YPlaybackFailureCategory.Network, timeout.category)

        val noRoute =
            yCoreUnavailableError("router", null, null, false, nativeOnly = true, probeTimedOut = false, null, null)
        assertEquals("YCore 2.0 纯内核路径无法打开当前片源", noRoute.message)
        assertEquals(YPlaybackFailureCategory.Unknown, noRoute.category)
    }

    @Test
    fun `a source failure and the compatibility hand-off keep their existing precedence`() {
        val kept = assertNotNull(yCoreRouteFailure(YPlaybackRoute.NativeDirect, null, null, null, hiddenAudio))
        val authorization =
            YPlaybackException(
                category = YPlaybackFailureCategory.Authorization,
                stage = YPlaybackFailureStage.SourceOpen,
            )
        assertEquals(
            YPlaybackFailureCategory.Authorization,
            yCoreUnavailableError("router", authorization, kept, false, true, false, null, null).category,
        )
        // With a compatibility engine the router stays Unknown so the Legacy fallback takes over.
        val compatibility = yCoreUnavailableError("router", null, kept, false, nativeOnly = false, true, null, null)
        assertEquals("YCore 2.0 与兼容内核均无法打开当前片源", compatibility.message)
        assertEquals(YPlaybackFailureCategory.Unknown, compatibility.category)
        // A protected start that timed out keeps its message and the Network category it had.
        val protectedStart = yCoreUnavailableError("router", null, null, true, nativeOnly = true, true, null, null)
        assertTrue(protectedStart.message.contains("受保护片源"))
        assertEquals(YPlaybackFailureCategory.Network, protectedStart.category)
    }

    @Test
    fun `a start that stops on a deadline shows the earlier concrete failure`() {
        val kept = assertNotNull(yCoreRouteFailure(YPlaybackRoute.NativeDirect, null, null, null, hiddenAudio))
        val timedOut =
            YPlayerState(
                phase = YPlaybackPhase.Failed,
                error = "YCore 2.0 无法连接片源，请检查服务器或网络",
                errorCategory = YPlaybackFailureCategory.Network,
                diagnostics = YPlayerDiagnostics(route = YPlaybackRoute.NativeEnhanced, reason = "enhanced"),
            )

        val published = timedOut.withStartFailure(attemptRanOutOfTime = true, kept = kept)

        assertEquals(YCORE_HIDDEN_AUDIO_TRACK_MESSAGE, published.error)
        assertEquals(YPlaybackFailureCategory.Container, published.errorCategory)
        assertEquals(
            "${kept.reason}; NativeEnhanced then ran out of its startup deadline",
            published.diagnostics.reason,
        )
        assertSame(timedOut, timedOut.withStartFailure(attemptRanOutOfTime = false, kept = kept))
        assertSame(timedOut, timedOut.withStartFailure(attemptRanOutOfTime = true, kept = null))
        val preparing = timedOut.copy(phase = YPlaybackPhase.Preparing)
        assertSame(preparing, preparing.withStartFailure(attemptRanOutOfTime = true, kept = kept))
    }

    @Test
    fun `Dolby Vision has no software recovery unless another executor can carry it`() {
        assertFalse(
            yCoreSoftwareRecoveryAvailable(
                compatibilityRouteAvailable = false,
                discRouteAvailable = false,
                protectedContent = false,
                inputHdrType = YHdrType.DolbyVision,
            ),
        )
        assertTrue(yCoreSoftwareRecoveryAvailable(false, false, false, YHdrType.Hdr10))
        assertTrue(yCoreSoftwareRecoveryAvailable(true, false, false, YHdrType.DolbyVision))
        assertTrue(yCoreSoftwareRecoveryAvailable(false, true, false, YHdrType.DolbyVision))
        assertFalse(yCoreSoftwareRecoveryAvailable(false, false, protectedContent = true, YHdrType.Sdr))
    }

    @Test
    fun `the Dolby guard blocks software video decode only`() {
        val hardwareVideo =
            YPlaybackPlan(
                route = YPlaybackRoute.SoftwareFallback,
                demuxPath = YDemuxPath.Enhanced,
                decodePath = YDecodePath.Hardware,
                renderPath = YRenderPath.SurfaceDirect,
                outputHdrType = YHdrType.DolbyVision,
                inputHdrType = YHdrType.DolbyVision,
                softwareAudioDecode = true,
                reason = "software audio",
            )
        val softwareVideo =
            hardwareVideo.copy(decodePath = YDecodePath.Software, renderPath = YRenderPath.Gpu)

        assertTrue(yCoreSoftwarePlanPassesDolbyGuard(hardwareVideo))
        assertTrue(yCoreSoftwarePlanPassesDolbyGuard(hardwareVideo.copy(decodePath = YDecodePath.PlatformSoftware)))
        assertFalse(yCoreSoftwarePlanPassesDolbyGuard(softwareVideo))
        val softwareHdr10 = softwareVideo.copy(inputHdrType = YHdrType.Hdr10, outputHdrType = YHdrType.Sdr)
        assertTrue(yCoreSoftwarePlanPassesDolbyGuard(softwareHdr10))
        assertFalse(yCoreSoftwarePlanPassesDolbyGuard(softwareHdr10.copy(usesHdrFallback = true)))
    }

    @Test
    fun `a startup failure recovered on the same route keeps the verified route`() {
        val recovered = YCoreVerifiedRouteSuspicion()
        recovered.onFailure(YPlaybackFailureCategory.Unknown)
        assertFalse(recovered.onRecovery(YPlaybackRecoveryAction.RetrySameRoute))
        recovered.onVideoOutput()
        assertFalse(recovered.suspect)
        assertFalse(recovered.onRecovery(YPlaybackRecoveryAction.Stop))

        val abandoned = YCoreVerifiedRouteSuspicion()
        abandoned.onFailure(YPlaybackFailureCategory.Decoder)
        assertFalse(abandoned.onRecovery(YPlaybackRecoveryAction.RetrySameRoute))
        abandoned.onFailure(YPlaybackFailureCategory.Decoder)
        assertTrue(abandoned.onRecovery(YPlaybackRecoveryAction.FallbackToEnhanced))
        // Forgotten once, not again for the next tier.
        assertFalse(abandoned.onRecovery(YPlaybackRecoveryAction.FallbackToSoftware))

        val neutral = YCoreVerifiedRouteSuspicion()
        for (category in listOf(YPlaybackFailureCategory.Network, YPlaybackFailureCategory.Drm, null)) {
            neutral.onFailure(category)
        }
        assertFalse(neutral.onRecovery(YPlaybackRecoveryAction.Stop))
    }

    @Test
    fun `failure memory is filed under the route that actually ran`() {
        val planned =
            YCore2FailureKey(
                route = YPlaybackRoute.SoftwareFallback,
                container = YContainer.Matroska,
                videoCodec = YVideoCodec.H265,
                hdrType = YHdrType.DolbyVision,
                dolbyVisionProfile = 5,
                decoderName = "c2.dolby.decoder",
            )

        val executed = planned.forExecutedRoute(YPlaybackRoute.NativeEnhanced)

        assertEquals(planned.copy(route = YPlaybackRoute.NativeEnhanced), executed)
        assertSame(planned, planned.forExecutedRoute(YPlaybackRoute.Legacy))
        assertSame(planned, planned.forExecutedRoute(YPlaybackRoute.SoftwareFallback))
    }
}
