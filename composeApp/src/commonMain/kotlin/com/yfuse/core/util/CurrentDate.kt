package com.yfuse.core.util

/** Current local calendar date in ISO-8601 form, suitable for TMDB date comparison. */
expect fun currentIsoDate(): String

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
