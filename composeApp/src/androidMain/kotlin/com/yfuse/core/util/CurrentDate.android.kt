package com.yfuse.core.util

import java.time.LocalDate

actual fun currentIsoDate(): String = LocalDate.now().toString()

actual fun isoDateDaysBefore(date: String, days: Int): String {
    require(days >= 0) { "days must not be negative" }
    return LocalDate.parse(date).minusDays(days.toLong()).toString()
}
