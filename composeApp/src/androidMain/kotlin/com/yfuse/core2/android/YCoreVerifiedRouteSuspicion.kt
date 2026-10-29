package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.recovery.YPlaybackRecoveryAction

/** Failure categories that say nothing about whether the remembered media facts still hold. */
private val VERIFIED_ROUTE_NEUTRAL_FAILURES =
    setOf(
        YPlaybackFailureCategory.Network,
        YPlaybackFailureCategory.Authorization,
        YPlaybackFailureCategory.Drm,
    )

/**
 * Decides when a local failure makes the router forget a route verified for this media.
 *
 * A local failure on the remembered route means the remembered facts may no longer describe this
 * media on this device; a transport, account or DRM failure says nothing about them. The route is
 * forgotten once recovery leaves it. A startup failure that the same route then recovers from and
 * renders (1.0.83: a NativeDirect IllegalStateException that played 2 s later) proves the facts,
 * and forgetting them made the next start of the title pay every probe again.
 */
internal class YCoreVerifiedRouteSuspicion {
    var suspect: Boolean = false
        private set

    fun onFailure(category: YPlaybackFailureCategory?) {
        if (category != null && category !in VERIFIED_ROUTE_NEUTRAL_FAILURES) suspect = true
    }

    fun onVideoOutput() {
        suspect = false
    }

    /** True when the route must be forgotten now, because recovery is leaving it or stopping. */
    fun onRecovery(action: YPlaybackRecoveryAction): Boolean {
        if (!suspect || action == YPlaybackRecoveryAction.RetrySameRoute) return false
        suspect = false
        return true
    }
}
