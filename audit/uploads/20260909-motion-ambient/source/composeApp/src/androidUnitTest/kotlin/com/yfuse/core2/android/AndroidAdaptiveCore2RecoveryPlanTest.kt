package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidAdaptiveCore2RecoveryPlanTest {
    @Test
    fun `explicit YCore never lets persisted route memory become a hard startup gate`() {
        assertTrue(
            shouldBypassLearnedYCoreRouteMemory(
                manualRetry = false,
                compatibilityRouteAvailable = false,
            ),
        )
        assertFalse(
            shouldBypassLearnedYCoreRouteMemory(
                manualRetry = false,
                compatibilityRouteAvailable = true,
            ),
        )
    }

    @Test
    fun `native direct never treats an unresolved Dolby source as ordinary HEVC`() {
        val failure =
            assertFailsWith<YPlaybackException> {
                validateNativeDirectDolbyIdentity(
                    required = true,
                    extractedMime = "video/hevc",
                )
            }

        assertEquals(YPlaybackFailureCategory.Container, failure.category)
        assertEquals(YPlaybackFailureStage.Bitstream, failure.stage)
    }

    @Test
    fun `native direct accepts the Dolby identity exposed by the platform extractor`() {
        validateNativeDirectDolbyIdentity(
            required = true,
            extractedMime = "video/dolby-vision",
        )
    }

    @Test
    fun `YCore internal recovery advances through enhanced hardware then software`() {
        val enhanced = yCoreInternalEnhancedRecoveryPlan(YHdrType.Hdr10)
        val software = assertNotNull(yCoreInternalSoftwareRecoveryPlan(YHdrType.Hdr10))

        assertEquals(YPlaybackRoute.NativeEnhanced, enhanced.route)
        assertEquals(YDemuxPath.Enhanced, enhanced.demuxPath)
        assertEquals(YDecodePath.Hardware, enhanced.decodePath)
        assertEquals(YRenderPath.SurfaceDirect, enhanced.renderPath)
        assertEquals(YPlaybackRoute.SoftwareFallback, software.route)
        assertEquals(YDecodePath.Software, software.decodePath)
        assertEquals(YRenderPath.Gpu, software.renderPath)
        assertEquals(YHdrType.Sdr, software.outputHdrType)
        assertTrue(software.softwareVideoToneMap)
    }

    @Test
    fun `Dolby Vision never crosses into ordinary software decode`() {
        assertNull(yCoreInternalSoftwareRecoveryPlan(YHdrType.DolbyVision))
        assertFailsWith<YPlaybackException> {
            validateEnhancedDolbyVisionIdentity(required = true, config = null)
        }
    }

    @Test
    fun `real playback failures replace the preflight gate with an actionable category`() {
        assertEquals(
            "YCore 2.0 无法连接片源，请检查服务器或网络",
            yCoreNativeDirectFailureMessage(
                YPlaybackException(
                    category = YPlaybackFailureCategory.Network,
                    stage = YPlaybackFailureStage.SourceOpen,
                ),
            ),
        )
    }

    @Test
    fun `silent native recovery stays inside the active serialized child`() {
        assertTrue(
            shouldRetryActiveNativeChildInPlace(
                nativeOnly = true,
                phase = YPlaybackPhase.Ready,
                route = YPlaybackRoute.NativeDirect,
            ),
        )
        assertFalse(
            shouldRetryActiveNativeChildInPlace(
                nativeOnly = false,
                phase = YPlaybackPhase.Ready,
                route = YPlaybackRoute.NativeDirect,
            ),
        )
        assertFalse(
            shouldRetryActiveNativeChildInPlace(
                nativeOnly = true,
                phase = YPlaybackPhase.Failed,
                route = YPlaybackRoute.NativeDirect,
            ),
        )
    }

    @Test
    fun `same native route codec recovery is serialized without rebuilding the child`() {
        assertTrue(canRetryCore2RouteInPlace(YPlaybackRoute.NativeDirect))
        assertTrue(canRetryCore2RouteInPlace(YPlaybackRoute.NativeEnhanced))
        assertFalse(canRetryCore2RouteInPlace(YPlaybackRoute.Legacy))
        assertFalse(canRetryCore2RouteInPlace(YPlaybackRoute.SoftwareFallback))
    }
}
