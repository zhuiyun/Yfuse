package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `inconclusive Dolby metadata receives a real YCore FFmpeg attempt`() {
        val plan =
            yCoreInconclusiveSourceRecoveryPlan(
                YMediaItem(
                    id = "dolby-unknown",
                    uri = "https://media.invalid/movie.mkv",
                    sourceHints =
                        YMediaSourceHints(
                            dynamicRange = "Dolby Vision",
                            dolbyVision = true,
                        ),
                ),
            )

        assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
        assertEquals(YDemuxPath.Enhanced, plan.demuxPath)
        assertEquals(YDecodePath.Software, plan.decodePath)
        assertEquals(YRenderPath.Gpu, plan.renderPath)
        assertEquals(YHdrType.DolbyVision, plan.inputHdrType)
        assertEquals(YHdrType.Sdr, plan.outputHdrType)
        assertTrue(plan.softwareVideoToneMap)
        assertTrue(plan.softwareAudioDecode)
        assertFalse(plan.usesHdrFallback)
    }

    @Test
    fun `real playback failures replace the preflight gate with an actionable category`() {
        assertEquals(
            "YCore 2.0 无法连接片源，请检查服务器或网络",
            yCoreEnhancedFailureMessage(
                YPlaybackException(
                    category = YPlaybackFailureCategory.Network,
                    stage = YPlaybackFailureStage.SourceOpen,
                ),
            ),
        )
    }
}
