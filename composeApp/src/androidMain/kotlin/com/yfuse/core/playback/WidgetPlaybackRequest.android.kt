package com.yfuse.core.playback

import java.net.CookieManager
import java.net.CookiePolicy

/**
 * A request owns its item snapshot, even while another queue item is playing or preloading.
 *
 * Carries the per-request custom headers (and their cookie jar) through the playback proxy and
 * the OkHttp stack as a request tag, so redirects keep the binding while cross-origin header
 * stripping can inspect it.
 */
internal class WidgetPlaybackRequest(
    val origin: String,
    val headers: PlayerCustomHeaders,
) {
    val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    override fun toString(): String = "WidgetPlaybackRequest([redacted])"
}
