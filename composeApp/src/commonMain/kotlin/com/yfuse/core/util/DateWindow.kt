package com.yfuse.core.util

/**
 * The calendar's date arithmetic, on ISO-8601 `YYYY-MM-DD` strings.
 *
 * Strings rather than a date type because that is what both ends already speak: TMDB
 * returns and filters on ISO dates, and [currentIsoDate] hands one back. Parsing them into
 * a platform date only to format them again would add a dependency and a timezone question
 * to arithmetic that is pure calendar counting.
 */

/** [date] shifted by [days], which may be negative. Returns [date] unchanged if unparseable. */
fun shiftIsoDate(date: String, days: Int): String {
    val parts = date.split('-')
    if (parts.size != 3) return date
    val year = parts[0].toIntOrNull() ?: return date
    val month = parts[1].toIntOrNull() ?: return date
    val day = parts[2].toIntOrNull() ?: return date
    var epoch = isoToEpochDay(year, month, day) + days
    val (y, m, d) = epochDayToIso(epoch)
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/** Days from [from] to [to]; negative when [to] is earlier. 0 for anything unparseable. */
fun daysBetweenIso(from: String, to: String): Int {
    val a = parseIso(from) ?: return 0
    val b = parseIso(to) ?: return 0
    return (isoToEpochDay(b.first, b.second, b.third) - isoToEpochDay(a.first, a.second, a.third)).toInt()
}

/**
 * `周四` — which day of the week an ISO date falls on.
 *
 * From the epoch day rather than a platform calendar: 1970-01-01 was a Thursday, so the
 * weekday is the epoch day modulo seven and needs no timezone and no date type. Kotlin's
 * `%` keeps the sign of the dividend, hence the extra wrap for dates before 1970.
 */
fun isoWeekdayLabel(date: String): String {
    val parsed = parseIso(date) ?: return ""
    val epochDay = isoToEpochDay(parsed.first, parsed.second, parsed.third)
    val index = (((epochDay + 3) % 7 + 7) % 7).toInt()
    return WEEKDAYS[index]
}

/** Indexed from Monday, which is where [isoWeekdayLabel]'s `+3` offset lands 1970-01-01. */
private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** `7-30` — a date short enough for a chip, since the year is never in question here. */
fun isoShortDate(date: String): String {
    val parsed = parseIso(date) ?: return date
    return "${parsed.second}-${parsed.third}"
}

/** Days since 1970-01-01 for an ISO date, or null when it is not one. */
fun isoEpochDay(date: String): Long? =
    parseIso(date)?.let { (y, m, d) -> isoToEpochDay(y, m, d) }

/**
 * Today's entry out of a pool: the same all day, a different one tomorrow.
 *
 * Rotating on the date rather than picking at random is what makes it a *pick* — two
 * people opening the app on the same day see the same thing, closing and reopening does
 * not reshuffle it, and it still moves on overnight. The pool wraps, so a list of twenty
 * comes back round in twenty days.
 */
fun <T> List<T>.pickForDay(date: String): T? {
    if (isEmpty()) return null
    val day = isoEpochDay(date) ?: return first()
    // Kotlin keeps the sign of the dividend, and dates before 1970 are negative.
    return this[(((day % size) + size) % size).toInt()]
}

private fun parseIso(date: String): Triple<Int, Int, Int>? {
    val parts = date.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return Triple(year, month, day)
}

/**
 * Days since 1970-01-01, by the civil-from-days algorithm (Howard Hinnant's), which is
 * exact for the proleptic Gregorian calendar and needs no leap-year special cases beyond
 * the era arithmetic it already does.
 */
private fun isoToEpochDay(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400).toLong()
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097 + doe - 719_468
}

private fun epochDayToIso(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}
