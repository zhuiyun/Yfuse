package com.yfuse.core2.recovery

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute

/** Bounded, route-local recovery decision. It never crosses a DRM or authorization boundary. */
enum class YPlaybackRecoveryAction {
    RetrySameRoute,
    DisableTunnel,
    FallbackToEnhanced,
    FallbackToSoftware,
    Stop,
}

data class YPlaybackRecoveryContext(
    val route: YPlaybackRoute,
    val category: YPlaybackFailureCategory?,
    val sameRouteAttempts: Int,
    val protectedContent: Boolean,
) {
    init {
        require(sameRouteAttempts >= 0)
    }
}

/**
 * Keeps transient codec/sink resets invisible while remaining fail-closed for protected media.
 * Network retry belongs to the transport layer, where request replay and response validation are
 * available; this policy must not blindly reopen a credential-bearing source.
 */
object YPlaybackRecoveryPolicy {
    fun decide(context: YPlaybackRecoveryContext): YPlaybackRecoveryAction {
        val category = context.category
        if (
            category == YPlaybackFailureCategory.Authorization ||
            category == YPlaybackFailureCategory.Drm ||
            category == YPlaybackFailureCategory.Network
        ) {
            return YPlaybackRecoveryAction.Stop
        }
        if (context.route == YPlaybackRoute.SoftwareFallback) {
            return YPlaybackRecoveryAction.Stop
        }
        if (context.route == YPlaybackRoute.NativeTunnel) {
            return if (context.protectedContent) {
                YPlaybackRecoveryAction.Stop
            } else {
                YPlaybackRecoveryAction.DisableTunnel
            }
        }
        if (
            category in SAME_ROUTE_RECOVERABLE_FAILURES &&
            context.sameRouteAttempts < MAX_SAME_ROUTE_ATTEMPTS
        ) {
            return YPlaybackRecoveryAction.RetrySameRoute
        }
        if (context.protectedContent) return YPlaybackRecoveryAction.Stop
        return when (context.route) {
            YPlaybackRoute.NativeDirect -> YPlaybackRecoveryAction.FallbackToEnhanced
            YPlaybackRoute.NativeEnhanced,
            YPlaybackRoute.GpuEnhanced,
            -> YPlaybackRecoveryAction.FallbackToSoftware
            YPlaybackRoute.Legacy,
            YPlaybackRoute.NativeTunnel,
            YPlaybackRoute.SoftwareFallback,
            -> YPlaybackRecoveryAction.Stop
        }
    }
}

private val SAME_ROUTE_RECOVERABLE_FAILURES =
    setOf(
        YPlaybackFailureCategory.Decoder,
        YPlaybackFailureCategory.Renderer,
        YPlaybackFailureCategory.AudioSink,
        YPlaybackFailureCategory.Unknown,
    )

private const val MAX_SAME_ROUTE_ATTEMPTS = 1
