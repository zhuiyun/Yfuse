package com.yfuse.core.util

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

actual fun currentIsoDate(): String = LocalDate.now().toString()

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual fun currentHourOfDay(): Int = LocalTime.now().hour

actual fun scheduledEpochMillis(
    date: String,
    time: String,
    timeZoneId: String,
): Long? =
    runCatching {
        LocalDateTime
            .of(LocalDate.parse(date), LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm")))
            .atZone(ZoneId.of(timeZoneId))
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

actual fun isoDateDaysBefore(
    date: String,
    days: Int,
): String {
    require(days >= 0) { "days must not be negative" }
    return LocalDate.parse(date).minusDays(days.toLong()).toString()
}

/**
 * Formatted from the system's own 12/24-hour setting.
 *
 * The player hides the status bar, so this is the only clock on screen while a film is
 * running — which is the whole reason it is here, and reason enough not to guess at a format
 * the user has already chosen once.
 */
actual fun currentClockTime(): String {
    val context = androidAppContext
    val pattern = if (context == null || DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return LocalTime.now().format(DateTimeFormatter.ofPattern(pattern))
}

/**
 * The process-wide application context, set once from `YfuseApp`.
 *
 * Follows the same module-level handoff as the image cache and offline manager, but is not
 * named for one consumer: the clock's 12/24-hour lookup and the background image's URI-grant
 * release both need the same object, and a second global for the second caller would be two
 * ways to answer one question.
 */
var androidAppContext: Context? = null
