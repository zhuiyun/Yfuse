package com.yfuse.watch

import java.time.LocalDate

internal object ChineseScheduleParser {
    private val datePattern = Regex("(?:(20\\d{2})年)?(1[0-2]|0?[1-9])月(3[01]|[12]\\d|0?[1-9])日")
    private val episodePattern =
        Regex("(?:第|更新|上线|会员|SVIP|vip|VIP)?\\s*((?:\\d{1,3}\\s*[、,，~～—\\-至到]\\s*)*\\d{1,3})\\s*集")
    private val dayAtStartPattern =
        Regex("^(3[01]|[12]\\d|0?[1-9])(?=\\s|SVIP|VIP|会员|周|$)(.*)$", RegexOption.IGNORE_CASE)
    private val monthHeaderPattern = Regex("^(1[0-2]|0?[1-9])月$")
    private val weekdayPattern = Regex("周([一二三四五六日天])")

    fun parse(
        raw: String,
        defaultYear: Int,
        accessTier: String? = null,
    ): Map<Int, String> {
        val explicit = parseExplicitDates(raw, defaultYear)
        if (accessTier == null) return explicit
        val grid = parseCalendarGrid(raw, defaultYear, accessTier)
        return mergeWithoutConflict(listOf(explicit, grid)) ?: emptyMap()
    }

    private fun parseExplicitDates(
        raw: String,
        defaultYear: Int,
    ): Map<Int, String> {
        val text =
            raw
                .replace('\u00a0', ' ')
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
        val dates = datePattern.findAll(text).toList()
        val result = linkedMapOf<Int, String>()
        dates.forEachIndexed { index, match ->
            val year = match.groupValues[1].toIntOrNull() ?: defaultYear
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            val date = runCatching { LocalDate.of(year, month, day).toString() }.getOrNull() ?: return@forEachIndexed
            val end = dates.getOrNull(index + 1)?.range?.first ?: minOf(text.length, match.range.last + 100)
            val block = text.substring(match.range.last + 1, end)
            episodePattern.findAll(block).forEach { episodeMatch ->
                if (isUpdateCount(episodeMatch.value)) return@forEach
                expandEpisodes(episodeMatch.groupValues[1]).forEach { episode ->
                    val previous = result.putIfAbsent(episode, date)
                    if (previous != null && previous != date) return emptyMap()
                }
            }
        }
        return result
    }

    private fun parseCalendarGrid(
        raw: String,
        defaultYear: Int,
        accessTier: String,
    ): Map<Int, String> {
        val plain =
            raw
                .replace(Regex("(?is)<[^>]+>"), " ")
                .replace("\r", "\n")
                .replace('\u00a0', ' ')
        var currentMonth =
            Regex("(?<!\\d)(1[0-2]|0?[1-9])月")
                .find(plain)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: chineseMonthIn(plain)
                ?: return emptyMap()
        var currentDate: LocalDate? = null
        var pendingPreferredTier = false
        val result = linkedMapOf<Int, String>()

        plain.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            monthHeaderPattern.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()?.let { month ->
                currentMonth = month
                currentDate = null
                pendingPreferredTier = false
                return@forEach
            }
            chineseMonthHeader(line)?.let { month ->
                currentMonth = month
                currentDate = null
                pendingPreferredTier = false
                return@forEach
            }

            weekdayPattern.find(line)?.groupValues?.get(1)?.let { weekday ->
                currentDate = alignToWeekday(currentDate, weekday)
            }

            val dayMatch = dayAtStartPattern.matchEntire(line)
            if (dayMatch != null) {
                val remainder = dayMatch.groupValues[2]
                val otherBareNumbers = Regex("(?<![:\\d])\\d{1,2}(?![:\\d])").findAll(remainder).count()
                if (otherBareNumbers >= 2 && "集" !in remainder) {
                    currentDate = null
                    pendingPreferredTier = false
                    return@forEach
                }
                val day = dayMatch.groupValues[1].toInt()
                currentDate = runCatching { LocalDate.of(defaultYear, currentMonth, day) }.getOrNull()
            }

            val markerOnly = line.matches(Regex("(?i)^(?:VIP|会员|VIP会员)$"))
            val fragments = preferredTierFragments(line, accessTier).toMutableList()
            if (pendingPreferredTier && fragments.isEmpty()) fragments += line
            if (currentDate != null) {
                fragments.forEach { fragment ->
                    episodePattern.findAll(fragment).forEach { episodeMatch ->
                        if (isUpdateCount(episodeMatch.value)) return@forEach
                        expandEpisodes(episodeMatch.groupValues[1]).forEach { episode ->
                            val date = currentDate!!.toString()
                            val previous = result.putIfAbsent(episode, date)
                            if (previous != null && previous != date) return emptyMap()
                        }
                    }
                }
            }
            pendingPreferredTier = markerOnly && isPreferredTierLine(line, accessTier)
        }
        return result
    }

    private fun isPreferredTierLine(
        line: String,
        accessTier: String,
    ): Boolean =
        when (accessTier) {
            "Member" -> "会员" in line && "非会员" !in line && !line.contains("SVIP", ignoreCase = true)
            "SviP" -> line.contains("SVIP", ignoreCase = true)
            "Free" -> "非会员" in line || "免费" in line
            else ->
                "会员" !in line &&
                    !line.contains("SVIP", ignoreCase = true) &&
                    !line.contains("VIP", ignoreCase = true)
        }

    private fun preferredTierFragments(
        line: String,
        accessTier: String,
    ): List<String> =
        when (accessTier) {
            "Member" ->
                Regex(
                    "(?i)(?<!S)(?:VIP)?会员.{0,100}?(?=SVIP|东方卫视|CCTV|非会员|$)",
                ).findAll(line).map(MatchResult::value).toList()
            "SviP" ->
                Regex("(?i)SVIP.{0,100}?(?=(?<!S)(?:VIP)?会员|东方卫视|CCTV|非会员|$)")
                    .findAll(line)
                    .map(MatchResult::value)
                    .toList()
            "Free" ->
                Regex("(?:非会员|免费).{0,100}?(?=SVIP|(?<!S)(?:VIP)?会员|东方卫视|CCTV|$)")
                    .findAll(line)
                    .map(MatchResult::value)
                    .toList()
            else -> if (isPreferredTierLine(line, accessTier)) listOf(line) else emptyList()
        }

    private fun alignToWeekday(
        date: LocalDate?,
        chineseWeekday: String,
    ): LocalDate? {
        date ?: return null
        val target =
            when (chineseWeekday) {
                "一" -> 1
                "二" -> 2
                "三" -> 3
                "四" -> 4
                "五" -> 5
                "六" -> 6
                else -> 7
            }
        if (date.dayOfWeek.value == target) return date
        val daysAhead = (target - date.dayOfWeek.value + 7) % 7
        return date.plusDays(daysAhead.toLong())
    }

    private fun chineseMonthIn(text: String): Int? =
        CHINESE_MONTHS.entries.firstOrNull { (label, _) -> label in text }?.value

    private fun chineseMonthHeader(line: String): Int? = CHINESE_MONTHS[line]

    private fun isUpdateCount(value: String): Boolean =
        value.replace(Regex("\\s+"), "").matches(Regex("(?:更新|上线)\\d{1,3}集"))

    private fun expandEpisodes(raw: String): List<Int> {
        val normalized = raw.replace(Regex("[至到~～—-]"), "-")
        val range = Regex("^(\\d{1,3})\\s*-\\s*(\\d{1,3})$").matchEntire(normalized.trim())
        if (range != null) {
            val first = range.groupValues[1].toInt()
            val last = range.groupValues[2].toInt()
            return if (first in 1..500 && last in first..500 && last - first <= 100) {
                (first..last).toList()
            } else {
                emptyList()
            }
        }
        return normalized
            .split(Regex("[、,，]"))
            .mapNotNull(String::toIntOrNull)
            .filter { it in 1..500 }
            .distinct()
    }

    private val CHINESE_MONTHS =
        mapOf(
            "壹月" to 1,
            "贰月" to 2,
            "叁月" to 3,
            "肆月" to 4,
            "伍月" to 5,
            "陆月" to 6,
            "柒月" to 7,
            "捌月" to 8,
            "玖月" to 9,
            "拾月" to 10,
            "拾壹月" to 11,
            "拾贰月" to 12,
        )
}
