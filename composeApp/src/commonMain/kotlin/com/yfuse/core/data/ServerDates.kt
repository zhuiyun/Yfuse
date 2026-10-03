@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.yfuse.core.data

import kotlin.time.Instant

/**
 * Epoch milliseconds of an Emby/Jellyfin date such as `2026-09-30T12:34:56.1234567Z`, or null
 * when it cannot be read. Servers write up to seven fraction digits, and some Emby builds leave
 * the zone off a UTC date. The `0001-01-01` placeholder some servers use for "never" is null.
 */
internal fun serverDateEpochMs(value: String): Long? {
    val trimmed = value.trim().takeIf(String::isNotEmpty) ?: return null
    val time = trimmed.substringAfter('T', missingDelimiterValue = "")
    val zoned =
        if (trimmed.endsWith('Z') || trimmed.endsWith('z') || '+' in time || '-' in time) {
            trimmed
        } else {
            "${trimmed}Z"
        }
    return runCatching { Instant.parse(zoned).toEpochMilliseconds() }
        .getOrNull()
        ?.takeIf { it > 0L }
}
