package com.yfuse.core.model

/**
 * The episode's own name when it says something its number does not.
 *
 * 短剧 libraries name episodes after their number — 第1集, 第01集, 01, EP01, Episode 1,
 * 第一集 — so a title composed as number plus name read 「第1集 · 第1集」. Blank names and names
 * that only repeat [number] give null; any other name comes back trimmed.
 */
fun episodeOwnName(
    name: String?,
    number: Int?,
): String? {
    val trimmed = name?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (number == null) return trimmed
    return trimmed.takeUnless { numberNamedBy(it) == number }
}

/**
 * 「第1集 · 婚礼」 or just 「第1集」: [numberLabel] of [number], then the name when it adds
 * anything. With neither, the raw name — possibly empty — is all there is.
 */
fun episodeTitle(
    number: Int?,
    name: String?,
    separator: String = " · ",
    numberLabel: (Int) -> String = { "第${it}集" },
): String =
    listOfNotNull(number?.let(numberLabel), episodeOwnName(name, number))
        .joinToString(separator)
        .ifEmpty { name?.trim().orEmpty() }

/**
 * 「45 秒」「1 分 35 秒」「2 分钟」「24 分钟」. Seconds show only under ten minutes, where the
 * whole-minute floor left a 59-second episode with no length at all and read 1:59 as 「1 分钟」.
 * [runtimeMinutes] is the fallback when the exact runtime is unknown.
 */
fun episodeRuntimeLabel(
    runtimeTicks: Long?,
    runtimeMinutes: Int? = null,
): String? {
    val totalSeconds = runtimeTicks?.takeIf { it > 0L }?.div(TICKS_PER_SECOND)?.takeIf { it > 0L }
    if (totalSeconds == null) return runtimeMinutes?.takeIf { it > 0 }?.let { "$it 分钟" }
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0L -> "$seconds 秒"
        totalSeconds >= SECONDS_SHOWN_BELOW || seconds == 0L -> "$minutes 分钟"
        else -> "$minutes 分 $seconds 秒"
    }
}

/** The number a name consists of — 第01集, EP1, S01E01, 第十二集 — or null for anything more. */
internal fun numberNamedBy(name: String): Int? {
    val compact = name.filterNot(Char::isWhitespace).lowercase()
    NUMBERED_NAME.matchEntire(compact)?.let { match ->
        val digits = match.groupValues[1]
        return digits.toIntOrNull() ?: chineseNumber(digits)
    }
    LATIN_NUMBERED_NAME.matchEntire(compact)?.let { match -> return match.groupValues[1].toIntOrNull() }
    return null
}

/** 一 … 九千九百九十九, with 零/〇 and 两; also positional digits such as 一零五. */
private fun chineseNumber(text: String): Int? {
    if (text.isEmpty() || text.length > MAX_CHINESE_NUMBER_LENGTH) return null
    if (text.none { it in CHINESE_UNITS }) {
        var positional = 0
        for (char in text) positional = positional * 10 + (CHINESE_DIGITS[char] ?: return null)
        return positional.takeIf { it > 0 }
    }
    var total = 0
    var digit = -1
    for (char in text) {
        val value = CHINESE_DIGITS[char]
        if (value != null) {
            digit = value
        } else {
            val unit = CHINESE_UNITS[char] ?: return null
            // 十二: a unit with no digit before it counts once.
            total += (if (digit < 0) 1 else digit) * unit
            digit = -1
        }
    }
    if (digit > 0) total += digit
    return total.takeIf { it > 0 }
}

private const val TICKS_PER_SECOND = 10_000_000L
private const val SECONDS_SHOWN_BELOW = 600L
private const val MAX_CHINESE_NUMBER_LENGTH = 8

private val CHINESE_DIGITS =
    mapOf(
        '零' to 0,
        '〇' to 0,
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
    )
private val CHINESE_UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000)

/** 第1集, 第01话, 1集, 01, 第一集 — 第 and the counter are both optional. */
private val NUMBERED_NAME = Regex("^第?([0-9]{1,5}|[零〇一二两三四五六七八九十百千]{1,8})[集话話回期]?$")

/** ep01, ep.1, episode1, e01, s01e01. */
private val LATIN_NUMBERED_NAME = Regex("^(?:s[0-9]{1,3})?(?:episode|ep|e)\\.?0*([0-9]{1,5})$")
