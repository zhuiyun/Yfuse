package com.yfuse.feature.person

import java.time.LocalDate
import java.time.Period

// 生卒 and 出生地 as one line each under the portrait — shared by the phone's page and the
// television's, and kept here as plain strings so the wording is tested without a screen.

/**
 * `1962年6月27日出生 · 63 岁`, `1920年1月1日 – 2000年12月31日 · 享年 80 岁`, `2000年12月31日逝世`;
 * null when neither date is known. [today] is `YYYY-MM-DD`.
 */
internal fun personLifeLine(
    birthDate: String?,
    deathDate: String?,
    today: String,
): String? {
    val birth = birthDate?.let(::isoDay)
    val death = deathDate?.let(::isoDay)
    return when {
        birth != null && death != null -> {
            val age = yearsBetween(birth, death)?.let { " · 享年 $it 岁" }.orEmpty()
            "${chineseDate(birth)} – ${chineseDate(death)}$age"
        }
        birth != null -> {
            val age = isoDay(today)?.let { yearsBetween(birth, it) }?.let { " · $it 岁" }.orEmpty()
            "${chineseDate(birth)}出生$age"
        }
        death != null -> "${chineseDate(death)}逝世"
        else -> null
    }
}

/** `出生于 香港`, or null. */
internal fun personBirthPlaceLine(place: String?): String? = place?.trim()?.ifBlank { null }?.let { "出生于 $it" }

internal fun chineseDate(day: LocalDate): String = "${day.year}年${day.monthValue}月${day.dayOfMonth}日"

private fun isoDay(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim().take(10)) }.getOrNull()

/** Whole years, and none at all for dates the wrong way round — a typo is not an age. */
private fun yearsBetween(
    from: LocalDate,
    to: LocalDate,
): Int? = Period.between(from, to).years.takeIf { !to.isBefore(from) && it in 0..MAX_PLAUSIBLE_AGE }

private const val MAX_PLAUSIBLE_AGE = 130
