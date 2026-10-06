package com.yfuse.core2.android

import java.net.URI

internal fun mediaCredentialOriginsMatch(
    origin: String,
    target: String,
): Boolean {
    // The same address is the same origin, including SMB addresses java.net.URI cannot parse.
    if (origin == target) return true
    return runCatching {
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
}

/**
 * The lowercase scheme of an address, read without parsing the rest. SMB addresses keep their
 * paths unencoded for jcifs, and java.net.URI rejects one as soon as a file name has a space.
 */
internal fun String.rawUriScheme(): String? {
    val scheme = substringBefore(':', missingDelimiterValue = "")
    if (scheme.isEmpty() || !scheme.first().isLetter()) return null
    if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
    return scheme.lowercase()
}

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
        normalized.startsWith("x-emby-") ||
        normalized.contains("auth") ||
        normalized.contains("token") ||
        normalized.contains("api-key") ||
        normalized.contains("apikey")
}
