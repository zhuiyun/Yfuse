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
 * Whether a manifest at [parent] may send YCore's proxy to [child]. An http(s) playlist may name any
 * http(s) address - CDNs are normal - an SMB playlist only its own server's SMB, an on-device
 * document only documents of the same provider. A remote playlist naming content:// or smb:// would
 * otherwise have the proxy read this device's documents, or the viewer's shares, on a stranger's
 * behalf; anything else (file://, ftp://) the player would open itself.
 */
internal fun adaptiveChildStaysInSchemeFamily(
    parent: String,
    child: String,
): Boolean {
    val parentScheme = parent.rawUriScheme() ?: return false
    val childScheme = child.rawUriScheme() ?: return false
    return when (parentScheme) {
        "http", "https" -> childScheme == "http" || childScheme == "https"
        "smb", "content" -> childScheme == parentScheme && parent.rawAuthority() == child.rawAuthority()
        else -> false
    }
}

/** The host (and port) of a hierarchical address, read without parsing it; user info is dropped. */
private fun String.rawAuthority(): String =
    substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .lowercase()

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
