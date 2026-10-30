package com.yfuse.core.data

import io.ktor.http.encodeURLPathPart

internal fun plexNumericId(
    value: String,
    field: String,
): String {
    val normalized = value.trim()
    require(normalized.toLongOrNull() != null) { "$field must be a numeric Plex id" }
    return normalized.encodeURLPathPart()
}
