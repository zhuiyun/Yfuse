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
    fun `a remote tunnel failure leaves tunnel instead of stopping playback`() {
        listOf(
            YPlaybackFailureCategory.Network,
            YPlaybackFailureCategory.Container,
            YPlaybackFailureCategory.Unknown,
            null,
        ).forEach { category ->
            assertEquals(
                YPlaybackRecoveryAction.DisableTunnel,
                YPlaybackRecoveryPolicy.decide(
                    YPlaybackRecoveryContext(
                        route = YPlaybackRoute.NativeTunnel,
                        category = category,
                        sameRouteAttempts = 2,
                        protectedContent = false,
                    ),
                ),
                "$category",
            )
        }
    }

    @Test
    fun `tunnel still stops at authorization drm and protected content`() {
        listOf(YPlaybackFailureCategory.Authorization, YPlaybackFailureCategory.Drm).forEach { category ->
            assertEquals(
                YPlaybackRecoveryAction.Stop,
                YPlaybackRecoveryPolicy.decide(context(YPlaybackRoute.NativeTunnel, category, attempts = 0)),
                category.name,
            )
        }
        assertEquals(
            YPlaybackRecoveryAction.Stop,
            YPlaybackRecoveryPolicy.decide(
                context(
                    route = YPlaybackRoute.NativeTunnel,
                    category = YPlaybackFailureCategory.Network,
                    attempts = 0,
                    protectedContent = true,
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

    @Test
    fun `a deterministic failure moves on instead of retrying the route that produced it`() {
        val expected =
            mapOf(
                YPlaybackRoute.NativeDirect to YPlaybackRecoveryAction.FallbackToEnhanced,
                YPlaybackRoute.NativeEnhanced to YPlaybackRecoveryAction.FallbackToSoftware,
                YPlaybackRoute.GpuEnhanced to YPlaybackRecoveryAction.FallbackToSoftware,
            )
        val sameRouteRecoverable =
            listOf(
                YPlaybackFailureCategory.Decoder,
                YPlaybackFailureCategory.Renderer,
                YPlaybackFailureCategory.AudioSink,
                YPlaybackFailureCategory.Unknown,
            )
        for ((route, action) in expected) {
            for (category in sameRouteRecoverable) {
                assertEquals(
                    action,
                    YPlaybackRecoveryPolicy.decide(
                        context(route, category, attempts = 0).copy(deterministic = true),
                    ),
                    "$route/$category",
                )
            }
        }
    }

    @Test
    fun `an enhanced failure stops with its own error when no software route can carry the input`() {
        // 1.0.83: Dolby Vision on NativeEnhanced failed twice, then asked for a software route that
        // Dolby Vision cannot use and ended with a generic message.
        val deterministic =
            context(YPlaybackRoute.NativeEnhanced, YPlaybackFailureCategory.Decoder, attempts = 0)
                .copy(deterministic = true, softwareFallbackAvailable = false)
        assertEquals(YPlaybackRecoveryAction.Stop, YPlaybackRecoveryPolicy.decide(deterministic))

        val transient =
            context(YPlaybackRoute.GpuEnhanced, YPlaybackFailureCategory.Decoder, attempts = 0)
                .copy(softwareFallbackAvailable = false)
        assertEquals(YPlaybackRecoveryAction.RetrySameRoute, YPlaybackRecoveryPolicy.decide(transient))
        assertEquals(
            YPlaybackRecoveryAction.Stop,
            YPlaybackRecoveryPolicy.decide(transient.copy(sameRouteAttempts = 1)),
        )
        // NativeDirect still has the enhanced route to try.
        assertEquals(
            YPlaybackRecoveryAction.FallbackToEnhanced,
            YPlaybackRecoveryPolicy.decide(
                context(YPlaybackRoute.NativeDirect, YPlaybackFailureCategory.Container, attempts = 0)
                    .copy(deterministic = true, softwareFallbackAvailable = false),
            ),
        )
    }

    @Test
    fun `a deterministic failure keeps the drm network protected content and tunnel rules`() {
        val ownedElsewhere =
            listOf(
                YPlaybackFailureCategory.Authorization,
                YPlaybackFailureCategory.Drm,
                YPlaybackFailureCategory.Network,
            )
        for (category in ownedElsewhere) {
            assertEquals(
                YPlaybackRecoveryAction.Stop,
                YPlaybackRecoveryPolicy.decide(
                    context(YPlaybackRoute.NativeDirect, category, attempts = 0).copy(deterministic = true),
                ),
                category.name,
            )
        }
        assertEquals(
            YPlaybackRecoveryAction.Stop,
            YPlaybackRecoveryPolicy.decide(
                context(
                    route = YPlaybackRoute.NativeDirect,
                    category = YPlaybackFailureCategory.Decoder,
                    attempts = 0,
                    protectedContent = true,
                ).copy(deterministic = true),
            ),
        )
        assertEquals(
            YPlaybackRecoveryAction.DisableTunnel,
            YPlaybackRecoveryPolicy.decide(
                context(YPlaybackRoute.NativeTunnel, YPlaybackFailureCategory.Decoder, attempts = 0)
                    .copy(deterministic = true),
            ),
        )
        assertEquals(
            YPlaybackRecoveryAction.Stop,
            YPlaybackRecoveryPolicy.decide(
                context(YPlaybackRoute.SoftwareFallback, YPlaybackFailureCategory.Decoder, attempts = 0)
                    .copy(deterministic = true),
            ),
        )
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
