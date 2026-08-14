package com.yfuse.core.network

import com.yfuse.core.security.VaultCrypto

/**
 * Returns a stable, account-scoped image cache key without persisting Emby's credential.
 * The original URL remains the request data and still carries the token to the server.
 */
internal fun imageCacheKeyForUrl(url: String): String = credentialScopedCacheKeyForUrl(url, "image")

/** Media3 otherwise persists the authenticated playback URI verbatim in CachedContentIndex. */
internal fun mediaCacheKeyForUrl(url: String): String = credentialScopedCacheKeyForUrl(url, "media")

private fun credentialScopedCacheKeyForUrl(
    url: String,
    cacheKind: String,
): String {
    val fragmentIndex = url.indexOf('#')
    val urlWithoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
    val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
    val queryIndex = urlWithoutFragment.indexOf('?')
    if (queryIndex < 0) return url

    val base = urlWithoutFragment.substring(0, queryIndex)
    val allParameters =
        urlWithoutFragment
            .substring(queryIndex + 1)
            .split('&')
            .filter(String::isNotEmpty)
    val credentials =
        allParameters.mapNotNull { parameter ->
            parameter
                .takeIf { it.isEmbyCredentialParameter() }
                ?.substringAfter('=', missingDelimiterValue = "")
        }
    val parameters = allParameters.filterNot(String::isEmbyCredentialParameter)

    val sanitizedUrl =
        buildString {
            append(base)
            if (parameters.isNotEmpty()) {
                append('?')
                append(parameters.joinToString("&"))
            }
            append(fragment)
        }
    if (credentials.isEmpty()) return sanitizedUrl

    // Emby access tokens are high-entropy values. A SHA-256 namespace keeps accounts isolated
    // without putting a reusable credential in Coil/Media3's on-disk cache index.
    val credentialDigest =
        cacheKeyCrypto
            .sha256(credentials.joinToString("\u0000").encodeToByteArray())
            .toHex()
    return "yfuse-$cacheKind-v2:$credentialDigest:$sanitizedUrl"
}

private fun String.isEmbyCredentialParameter(): Boolean =
    substringBefore('=').let { name ->
        name.equals("api_key", ignoreCase = true) ||
            name.equals("X-Emby-Token", ignoreCase = true)
    }

private fun ByteArray.toHex(): String =
    buildString(size * 2) {
        this@toHex.forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0F])
        }
    }

private val cacheKeyCrypto by lazy(::VaultCrypto)
private const val HEX_DIGITS = "0123456789abcdef"
