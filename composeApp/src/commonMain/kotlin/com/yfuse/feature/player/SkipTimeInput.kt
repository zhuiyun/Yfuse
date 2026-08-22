package com.yfuse.feature.player

/**
 * Accepts plain seconds, `mm:ss`, or `hh:mm:ss` for the manual intro/credits editor.
 * A blank field deliberately means zero so clearing a field can clear that stored boundary.
 */
internal fun parseSkipTimestamp(input: String): Long? {
    val value = input.trim().replace('：', ':')
    if (value.isEmpty()) return 0L
    if (':' !in value) return value.toLongOrNull()?.takeIf { it >= 0L }

    val parts = value.split(':')
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { char -> !char.isDigit() } }) {
        return null
    }
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    val seconds = numbers.last()
    val minutes = numbers[numbers.lastIndex - 1]
    if (seconds !in 0L..59L || minutes !in 0L..59L) return null
    return if (numbers.size == 3) {
        val hours = numbers[0]
        hours * 3_600L + minutes * 60L + seconds
    } else {
        minutes * 60L + seconds
    }
}

internal fun formatSkipTimestamp(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = (safe % 3_600L) / 60L
    val remainder = safe % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    }
}

/** Stored credits are a distance from the end even though the editor shows a normal timestamp. */
internal fun creditsLeadSecondsFromStart(
    creditsStartSeconds: Long,
    durationSeconds: Long,
): Long? {
    if (durationSeconds <= 0L || creditsStartSeconds <= 0L || creditsStartSeconds >= durationSeconds) {
        return null
    }
    return durationSeconds - creditsStartSeconds
}

internal fun creditsStartSecondsFromLead(
    creditsLeadSeconds: Long,
    durationSeconds: Long,
): Long? {
    if (durationSeconds <= 0L || creditsLeadSeconds <= 0L || creditsLeadSeconds >= durationSeconds) {
        return null
    }
    return durationSeconds - creditsLeadSeconds
}
