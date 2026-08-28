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
        assertEquals(YPlaybackRecoveryAction.FallbackToSoftware, YPlaybackRecoveryPolicy.decide(second))
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
