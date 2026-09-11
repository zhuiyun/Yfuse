package com.yfuse.core.offline

val offlineVideoBudgetOptions: List<Long> = listOf(0L, 5L, 10L, 20L, 50L).map { it * 1024 * 1024 * 1024 }

val offlineDownloadWindowOptions: List<Pair<Int, Int>> = listOf(0 to 0, 22 * 60 to 7 * 60, 9 * 60 to 18 * 60)

fun offlineVideoBudgetLabel(bytes: Long): String = if (bytes <= 0) "不限" else "${bytes / (1024 * 1024 * 1024)} GB"

fun offlineDownloadWindowLabel(
    start: Int,
    end: Int,
): String {
    fun time(minute: Int) = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
    return if (start == end) "全天" else "${time(start)}–${time(end)}"
}

/** Equal endpoints mean all day. Handles a window such as 22:00–07:00 across midnight. */
fun OfflineDownloadPolicy.minutesUntilDownloadWindow(minuteOfDay: Int): Int {
    val minute = minuteOfDay.coerceIn(0, 1439)
    val start = windowStartMinute.coerceIn(0, 1439)
    val end = windowEndMinute.coerceIn(0, 1439)
    val allowed =
        when {
            start == end -> true
            start < end -> minute >= start && minute < end
            else -> minute >= start || minute < end
        }
    return if (allowed) 0 else (start - minute + 1440) % 1440
}

fun offlineBudgetAllows(
    limitBytes: Long,
    usedBytes: Long,
    additionalBytes: Long,
): Boolean =
    limitBytes <= 0L ||
        (
            usedBytes >= 0L &&
                additionalBytes >= 0L &&
                usedBytes <= limitBytes &&
                additionalBytes <= limitBytes - usedBytes
        )
