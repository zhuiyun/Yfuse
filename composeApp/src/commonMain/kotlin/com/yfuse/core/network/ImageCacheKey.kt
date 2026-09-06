package com.yfuse.core.network

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
                .takeIf { it.isServerCredentialParameter() }
                ?.substringAfter('=', missingDelimiterValue = "")
        }
    val parameters = allParameters.filterNot(String::isServerCredentialParameter)

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

    // The server and item are already in the URL; that is the identity a cached poster or
    // stream has. Namespacing by the token used to throw the whole disk cache away on every
    // re-login, and put nothing in it that the URL did not already say.
    return "yfuse-$cacheKind-v3:$sanitizedUrl"
}

private fun String.isServerCredentialParameter(): Boolean =
    substringBefore('=').let { name ->
        name.equals("api_key", ignoreCase = true) ||
            name.equals("X-Emby-Token", ignoreCase = true) ||
            name.equals("X-Plex-Token", ignoreCase = true)
    }
