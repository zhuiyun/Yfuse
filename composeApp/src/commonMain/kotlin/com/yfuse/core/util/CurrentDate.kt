package com.yfuse.core.util

/** Current local calendar date in ISO-8601 form, suitable for TMDB date comparison. */
expect fun currentIsoDate(): String

/** Subtracts whole calendar days from an ISO-8601 date without introducing a time zone. */
expect fun isoDateDaysBefore(date: String, days: Int): String
