package com.yfuse.core.util

/** Current local calendar date in ISO-8601 form, suitable for TMDB date comparison. */
expect fun currentIsoDate(): String

/** Current wall-clock instant, used when an official schedule includes an exact time. */
expect fun currentEpochMillis(): Long

/**
 * Converts a platform-published local date/time into an instant.
 *
 * Returns null for malformed input or an unavailable time-zone id so callers can degrade to
 * date-only semantics instead of making a confident but wrong broadcast claim.
 */
expect fun scheduledEpochMillis(
    date: String,
    time: String,
    timeZoneId: String,
): Long?

/** Subtracts whole calendar days from an ISO-8601 date without introducing a time zone. */
expect fun isoDateDaysBefore(
    date: String,
    days: Int,
): String

/**
 * Wall-clock time as the user's own locale writes it — 24-hour or 12-hour, per their device
 * setting rather than per this app's opinion.
 */
expect fun currentClockTime(): String
