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
    /** Intent is authoritative even when a failed executor still reports its old hardware route. */
    val softwareFallbackAttempted: Boolean = false,
    /**
     * The failure repeats whenever this route reopens the same input
     * ([com.yfuse.core2.api.YPlaybackException.deterministic]), so a same-route retry only adds
     * time: 1.0.83 spent 2.9 s re-running a codec configuration its own parser had rejected.
     */
    val deterministic: Boolean = false,
    /**
     * A software route can still carry this input. Dolby Vision cannot be decoded in software, so
     * without a compatibility executor an enhanced failure there has nowhere left to go.
     */
    val softwareFallbackAvailable: Boolean = true,
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
        if (category == YPlaybackFailureCategory.Authorization || category == YPlaybackFailureCategory.Drm) {
            return YPlaybackRecoveryAction.Stop
        }
        if (context.route == YPlaybackRoute.SoftwareFallback || context.softwareFallbackAttempted) {
            return YPlaybackRecoveryAction.Stop
        }
        // Checked before the Network stop: leaving Tunnel is a route change, not a replay of the
        // failed request, and the platform path opens its own validated reader. Tunnel reports its
        // transport stalls as Network, and stopping there stranded titles NativeDirect plays. It
        // happens once per item, because the rebuilt route never selects Tunnel again.
        if (context.route == YPlaybackRoute.NativeTunnel) {
            return if (context.protectedContent) {
                YPlaybackRecoveryAction.Stop
            } else {
                YPlaybackRecoveryAction.DisableTunnel
            }
        }
        if (category == YPlaybackFailureCategory.Network) return YPlaybackRecoveryAction.Stop
        if (
            !context.deterministic &&
            category in SAME_ROUTE_RECOVERABLE_FAILURES &&
            context.sameRouteAttempts < MAX_SAME_ROUTE_ATTEMPTS
        ) {
            return YPlaybackRecoveryAction.RetrySameRoute
        }
        if (context.protectedContent) return YPlaybackRecoveryAction.Stop
        // Stopping publishes the enhanced route's own typed failure. A software request with no
        // executable route ended as the generic "纯内核路径无法打开当前片源" (Unknown) instead.
        val afterEnhanced =
            if (context.softwareFallbackAvailable) {
                YPlaybackRecoveryAction.FallbackToSoftware
            } else {
                YPlaybackRecoveryAction.Stop
            }
        return when (context.route) {
            // The enhanced demuxer and bitstream normalizer read the input differently, so even a
            // deterministic platform failure is worth one enhanced attempt.
            YPlaybackRoute.NativeDirect -> YPlaybackRecoveryAction.FallbackToEnhanced
            YPlaybackRoute.NativeEnhanced,
            YPlaybackRoute.GpuEnhanced,
            -> afterEnhanced
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
