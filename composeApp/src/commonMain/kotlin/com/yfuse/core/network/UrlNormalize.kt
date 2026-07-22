package com.yfuse.core.network

/**
 * Trims trailing slashes and lowercases the URL scheme.
 *
 * Ktor tolerates an uppercase scheme (e.g. "HTTP://"), but Coil's network
 * fetcher only matches lowercase "http"/"https", so image URLs built from an
 * un-normalized base silently fail to load. Normalize once at the boundary.
 */
fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    return if (schemeEnd > 0) {
        trimmed.substring(0, schemeEnd).lowercase() + trimmed.substring(schemeEnd)
    } else {
        trimmed
    }
}
