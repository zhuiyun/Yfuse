package com.yfuse.core.offline

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.net.URL

internal fun URL.offlineSameOrigin(other: URL): Boolean =
    protocol.equals(other.protocol, true) &&
        host.equals(other.host, true) &&
        (if (port == -1) defaultPort else port) == (if (other.port == -1) other.defaultPort else other.port)

/** A CDN refusal may be an expired signed link. An origin login refusal is not renewed blindly. */
internal fun shouldRenewOfflineSource(
    status: Int,
    redirected: Boolean,
    negotiated: Boolean,
): Boolean = status in setOf(401, 403, 410) && (redirected || negotiated)

/** At most one renewal per open. Range/If-Range survive; the caller still validates the final entity. */
internal suspend fun openRenewableOfflineTransfer(
    initialSource: URL,
    userAgent: String,
    negotiated: Boolean,
    renewSource: suspend () -> URL,
    configure: HttpURLConnection.() -> Unit = {},
): HttpURLConnection {
    currentCoroutineContext().ensureActive()
    val first = openOfflineTransfer(initialSource, userAgent, configure)
    if (!shouldRenewOfflineSource(
            first.responseCode,
            !first.url.offlineSameOrigin(initialSource),
            negotiated,
        )
    ) {
        return first
    }
    first.disconnect()
    currentCoroutineContext().ensureActive()
    val refreshed = renewSource()
    currentCoroutineContext().ensureActive()
    return openOfflineTransfer(refreshed, userAgent, configure)
}
