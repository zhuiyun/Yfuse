package com.yfuse.core2.android

import java.net.URI

internal fun mediaCredentialOriginsMatch(
    origin: String,
    target: String,
): Boolean =
    runCatching {
        val first = URI(origin)
        val second = URI(target)

        fun port(uri: URI): Int =
            if (uri.port >= 0) {
                uri.port
            } else if (uri.scheme.equals("https", true)) {
                443
            } else {
                80
            }
        first.host != null &&
            first.host.equals(second.host, true) &&
            first.scheme.equals(second.scheme, true) &&
            port(first) == port(second)
    }.getOrDefault(false)

internal fun scopedMediaHeaders(
    headers: Map<String, String>,
    origin: String?,
    target: String,
): Map<String, String> =
    if (origin != null && mediaCredentialOriginsMatch(origin, target)) {
        headers
    } else {
        headers.filterKeys { !it.isCredentialHeader() }
    }

internal fun String.isCredentialHeader(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "authorization" ||
        normalized == "proxy-authorization" ||
        normalized == "cookie" ||
        normalized == "cookie2" ||
        normalized.contains("auth") ||
        normalized.contains("token") ||
        normalized.contains("api-key") ||
        normalized.contains("apikey")
}
