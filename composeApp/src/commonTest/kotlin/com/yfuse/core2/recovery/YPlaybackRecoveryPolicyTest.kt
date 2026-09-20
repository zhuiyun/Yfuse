package com.yfuse.core2.recovery

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class YPlaybackRecoveryPolicyTest {
    @Test
    fun `transient native codec failure gets one same-route rebuild`() {
        val first =
            context(
                route = YPlaybackRoute.NativeDirect,
                category = YPlaybackFailureCategory.Decoder,
                attempts = 0,
            )
        val second = first.copy(sameRouteAttempts = 1)

        assertEquals(YPlaybackRecoveryAction.RetrySameRoute, YPlaybackRecoveryPolicy.decide(first))
        assertEquals(YPlaybackRecoveryAction.FallbackToEnhanced, YPlaybackRecoveryPolicy.decide(second))
    }

    @Test
    fun `enhanced hardware failure advances to YCore software decode`() {
        assertEquals(
            YPlaybackRecoveryAction.FallbackToSoftware,
            YPlaybackRecoveryPolicy.decide(
                context(
                    route = YPlaybackRoute.NativeEnhanced,
                    category = YPlaybackFailureCategory.Decoder,
                    attempts = 1,
                ),
            ),
        )
    }

    @Test
    fun `tunnel failure disables tunnel instead of repeating it`() {
        assertEquals(
            YPlaybackRecoveryAction.DisableTunnel,
            YPlaybackRecoveryPolicy.decide(
                context(
                    route = YPlaybackRoute.NativeTunnel,
                    category = YPlaybackFailureCategory.Decoder,
                    attempts = 0,
                ),
            ),
        )
    }

    @Test
    fun `protected content never crosses into insecure software fallback`() {
        assertEquals(
            YPlaybackRecoveryAction.Stop,
            YPlaybackRecoveryPolicy.decide(
                context(
                    route = YPlaybackRoute.NativeDirect,
                    category = YPlaybackFailureCategory.Decoder,
                    attempts = 1,
                    protectedContent = true,
                ),
            ),
        )
        assertEquals(
            YPlaybackRecoveryAction.Stop,
            YPlaybackRecoveryPolicy.decide(
                context(
                    route = YPlaybackRoute.NativeDirect,
                    category = YPlaybackFailureCategory.Drm,
                    attempts = 0,
                    protectedContent = true,
                ),
            ),
        )
    }

    @Test
    fun `network and authorization failures are left to their owning layers`() {
        listOf(
            YPlaybackFailureCategory.Network,
            YPlaybackFailureCategory.Authorization,
        ).forEach { category ->
            assertEquals(
                YPlaybackRecoveryAction.Stop,
                YPlaybackRecoveryPolicy.decide(
                    context(YPlaybackRoute.NativeEnhanced, category, attempts = 0),
                ),
            )
        }
    }

    @Test
    fun `an attempted software route cannot loop through a reported hardware route`() {
        for (route in YPlaybackRoute.entries) {
            for (category in YPlaybackFailureCategory.entries) {
                assertEquals(
                    YPlaybackRecoveryAction.Stop,
                    YPlaybackRecoveryPolicy.decide(
                        context(route, category, attempts = 0).copy(softwareFallbackAttempted = true),
                    ),
                    "$route/$category",
                )
            }
        }
    }

    @Test
    fun `a hardware disc failure gets one retry and one software transition`() {
        var request = context(YPlaybackRoute.NativeEnhanced, YPlaybackFailureCategory.Decoder, attempts = 0)
        assertEquals(YPlaybackRecoveryAction.RetrySameRoute, YPlaybackRecoveryPolicy.decide(request))
        request = request.copy(sameRouteAttempts = 1)
        assertEquals(YPlaybackRecoveryAction.FallbackToSoftware, YPlaybackRecoveryPolicy.decide(request))
        // A faulty executor may retain its hardware label after the software request.
        request = request.copy(softwareFallbackAttempted = true)
        repeat(3) {
            assertEquals(YPlaybackRecoveryAction.Stop, YPlaybackRecoveryPolicy.decide(request))
        }
    }

    private fun context(
        route: YPlaybackRoute,
        category: YPlaybackFailureCategory,
        attempts: Int,
        protectedContent: Boolean = false,
    ) = YPlaybackRecoveryContext(
        route = route,
        category = category,
        sameRouteAttempts = attempts,
        protectedContent = protectedContent,
    )
}
