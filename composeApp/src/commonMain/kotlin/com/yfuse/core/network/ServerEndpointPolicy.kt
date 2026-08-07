package com.yfuse.core.network

import com.yfuse.core.model.SavedServer
import io.ktor.http.Url

private val retiredEmbyHosts =
    setOf(
        "gf.emby.yun",
        "gy.emby.yun",
    )

/**
 * Returns a user-facing reason when an endpoint is known to be permanently unavailable.
 *
 * These two legacy addresses use `.yun`, which is not a public DNS suffix. Keeping the saved
 * entries lets the user edit or remove them, but sending a request can only spend the full DNS
 * or connection budget. Matching the parsed host (not the raw URL) handles ports without
 * accidentally rejecting a longer, otherwise valid hostname.
 */
internal fun SavedServer.knownUnavailableEndpointReason(): String? {
    val host =
        runCatching { Url(baseUrl).host.trimEnd('.').lowercase() }.getOrNull()
            ?: return null
    return if (host in retiredEmbyHosts) {
        "服务器地址已失效，请编辑或移除该服务器"
    } else {
        null
    }
}
