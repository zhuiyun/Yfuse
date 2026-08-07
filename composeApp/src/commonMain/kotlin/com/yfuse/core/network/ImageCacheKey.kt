package com.yfuse.core.network

/**
 * Returns a stable image cache key with Emby's credential query parameter removed.
 * The original URL remains the request data and still carries the token to the server.
 */
internal fun imageCacheKeyForUrl(url: String): String {
    val fragmentIndex = url.indexOf('#')
    val urlWithoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
    val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
    val queryIndex = urlWithoutFragment.indexOf('?')
    if (queryIndex < 0) return url

    val base = urlWithoutFragment.substring(0, queryIndex)
    val parameters = urlWithoutFragment
        .substring(queryIndex + 1)
        .split('&')
        .filterNot { parameter ->
            parameter.substringBefore('=').equals("api_key", ignoreCase = true)
        }

    return buildString {
        append(base)
        if (parameters.isNotEmpty()) {
            append('?')
            append(parameters.joinToString("&"))
        }
        append(fragment)
    }
}
