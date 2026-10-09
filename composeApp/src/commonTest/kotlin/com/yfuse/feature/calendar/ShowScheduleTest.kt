package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringAccessTier
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarSource
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShowScheduleTest {
    @Test
    fun a_month_is_laid_out_in_weeks_that_start_on_sunday() {
        val october = scheduleMonthWeeks(ScheduleMonth(2026, 10))

        assertEquals(5, october.size)
        // 2026-10-01 is a Thursday: four days of September lead the first week.
        assertEquals(
            listOf(null, null, null, null, "2026-10-01", "2026-10-02", "2026-10-03"),
            october.first(),
        )
        assertEquals("2026-10-31", october.last()[6])
        assertTrue(october.all { it.size == 7 })

        // A month that starts on a Sunday and fills four weeks exactly.
        val february = scheduleMonthWeeks(ScheduleMonth(2026, 2))
        assertEquals(4, february.size)
        assertEquals("2026-02-01", february.first().first())
        assertEquals("2026-02-28", february.last().last())

        // December runs into January without borrowing its days.
        val december = scheduleMonthWeeks(ScheduleMonth(2026, 12))
        assertEquals("2026-12-31", december.last()[4])
        assertEquals(listOf(null, null), december.last().takeLast(2))
    }

    @Test
    fun months_skip_the_empty_ones_between_broadcasts() {
        val months = scheduleMonths(listOf("2026-10-03", "2026-08-30", "2026-10-01", "not-a-date"))

        assertEquals(listOf(ScheduleMonth(2026, 8), ScheduleMonth(2026, 10)), months)
        assertEquals("2026年10月", months.last().label)
        assertEquals(ScheduleMonth(2027, 1), ScheduleMonth(2026, 12).next)
        assertNull(scheduleMonthOf("2026-13-01"))
    }

    @Test
    fun neighbouring_broadcast_days_share_one_stroke() {
        assertEquals(listOf(4..6), scheduleStrokeRuns(listOf(false, false, false, false, true, true, true)))
        assertEquals(
            listOf(0..0, 2..3, 6..6),
            scheduleStrokeRuns(listOf(true, false, true, true, false, false, true)),
        )
        assertEquals(emptyList(), scheduleStrokeRuns(List(7) { false }))
    }

    @Test
    fun a_day_names_the_specific_episodes_including_gaps_and_season_changes() {
        val single = scheduleDaySpan(listOf(entry(39, "2026-10-01")))
        assertEquals(ScheduleDaySpan.Run(39, 39), single)
        assertEquals("39", single?.cellLabel)
        assertEquals("第 39 集", single?.phrase)

        val run = scheduleDaySpan((40..44).map { entry(it, "2026-10-02") })
        assertEquals("40-44", run?.cellLabel)
        assertEquals("第 40-44 集", run?.phrase)

        // A gap must not be claimed by a range or hidden behind a count.
        val gapped = scheduleDaySpan(listOf(entry(41, "2026-10-03"), entry(45, "2026-10-03")))
        assertEquals(ScheduleDaySpan.Episodes(listOf(1 to 41, 1 to 45)), gapped)
        assertEquals("41,45", gapped?.cellLabel)
        assertEquals("第 41、45 集", gapped?.phrase)

        val twoSeasons = scheduleDaySpan(listOf(entry(10, "2026-10-03"), entry(1, "2026-10-03", season = 2)))
        assertEquals(ScheduleDaySpan.Episodes(listOf(1 to 10, 2 to 1)), twoSeasons)
        assertEquals("S1E10/S2E1", twoSeasons?.cellLabel)
        assertEquals("第 1 季第 10 集、第 2 季第 1 集", twoSeasons?.phrase)

        // The same episode from two platforms is still one episode.
        assertEquals(
            ScheduleDaySpan.Run(42, 42),
            scheduleDaySpan(listOf(entry(42, "2026-10-04"), entry(42, "2026-10-04"))),
        )

        assertEquals("上映", scheduleDaySpan(listOf(movie("2026-10-08")))?.cellLabel)
        assertNull(scheduleDaySpan(emptyList()))
    }

    @Test
    fun the_season_line_says_what_has_aired_and_what_is_still_dated() {
        val entries =
            listOf(
                entry(39, "2026-10-01"),
                entry(40, "2026-10-02"),
                entry(41, "2026-10-02"),
                entry(42, "2026-10-04", status = LibraryStatus.Unaired),
                entry(43, "2026-10-05", status = LibraryStatus.Unaired),
            )

        assertEquals("第 1 季 · 更新至第 41 集 · 待播 2 集", scheduleSeasonSummary(entries, today = "2026-10-03"))

        // Tonight's episode still waits until the repository's clock says it has started.
        val tonight = entries + entry(42, "2026-10-03", status = LibraryStatus.Unaired)
        assertEquals("第 1 季 · 更新至第 41 集 · 待播 2 集", scheduleSeasonSummary(tonight, today = "2026-10-03"))

        val premiere =
            listOf(
                entry(1, "2026-10-08", season = 2, status = LibraryStatus.Unaired),
                entry(2, "2026-10-09", season = 2, status = LibraryStatus.Unaired),
            )
        assertEquals("第 2 季 · 10月8日开播 · 待播 2 集", scheduleSeasonSummary(premiere, today = "2026-10-03"))

        val finished = listOf(entry(1, "2026-09-01", season = 0), entry(2, "2026-09-08", season = 0))
        assertEquals("特别篇 · 更新至第 2 集", scheduleSeasonSummary(finished, today = "2026-10-03"))

        assertEquals("10月8日上映", scheduleSeasonSummary(listOf(movie("2026-10-08")), today = "2026-10-03"))
        assertNull(scheduleSeasonSummary(emptyList(), today = "2026-10-03"))
    }

    @Test
    fun the_card_opens_on_the_broadcast_closest_to_today() {
        val dates = listOf("2026-08-26", "2026-08-27", "2026-09-02")

        assertEquals("2026-09-02", scheduleInitialDate(dates, "2026-08-31"))
        assertNull(scheduleInitialDate(emptyList(), "2026-08-31"))
    }

    @Test
    fun dates_are_written_in_chinese_and_relative_to_today() {
        assertEquals("10月3日", scheduleMonthDay("2026-10-03"))
        assertEquals("unknown", scheduleMonthDay("unknown"))
        assertEquals("昨天", scheduleRelativeDay("2026-08-24", "2026-08-25"))
        assertEquals("今天", scheduleRelativeDay("2026-08-25", "2026-08-25"))
        assertEquals("明天", scheduleRelativeDay("2026-08-26", "2026-08-25"))
        assertEquals("5 天后", scheduleRelativeDay("2026-08-30", "2026-08-25"))
        assertEquals("3 天前", scheduleRelativeDay("2026-08-22", "2026-08-25"))
    }

    @Test
    fun provenance_names_the_strongest_source_the_schedule_has() {
        val official =
            entry(42, "2026-10-04").let {
                it.copy(
                    episode =
                        it.episode.copy(
                            scheduleAuthority = AiringScheduleAuthority.Official,
                            platforms = listOf("腾讯视频"),
                            sourceUrl = "https://v.qq.com/schedule",
                            airTime = "20:00",
                            timeZoneId = "Asia/Shanghai",
                        ),
                )
            }

        val provenance = scheduleProvenance(listOf(entry(41, "2026-10-03"), official), deviceZoneId = "Asia/Shanghai")

        assertEquals("官方会员日历", provenance.authority)
        assertEquals(listOf("腾讯视频"), provenance.platforms)
        assertEquals("https://v.qq.com/schedule", provenance.sourceUrl)
        assertEquals("20:00", provenance.airTime)

        val tmdbOnly = scheduleProvenance(listOf(entry(1, "2026-10-01")), deviceZoneId = "Asia/Shanghai")
        assertEquals("按原产地播出日期", tmdbOnly.authority)
        assertNull(tmdbOnly.sourceUrl)
        assertNull(tmdbOnly.airTime)
    }

    @Test
    fun episode_small_print_carries_time_platform_and_tier() {
        val episode =
            entry(42, "2026-10-04").episode.copy(
                airTime = "20:00",
                timeZoneId = "Asia/Shanghai",
                platforms = listOf("腾讯视频", "", "爱奇艺", "优酷"),
                accessTier = AiringAccessTier.SviP,
            )

        assertEquals("20:00 · 腾讯视频/爱奇艺 · SVIP", scheduleEpisodeMeta(episode, deviceZoneId = "Asia/Shanghai"))
        assertNull(scheduleEpisodeMeta(entry(1, "2026-10-01").episode, deviceZoneId = "Asia/Shanghai"))
    }

    @Test
    fun the_footer_names_the_servers_and_what_they_hold() {
        val held =
            entry(41, "2026-10-03").copy(
                sources =
                    listOf(
                        CalendarSource(
                            serverId = "a",
                            serverName = "Rex",
                            status = LibraryStatus.Available,
                            libraryEpisodeCount = 39,
                        ),
                    ),
            )

        assertEquals("Rex · 已入库 39 集", scheduleLibraryLine(listOf(held)))
        assertEquals(
            "未连接媒体库",
            scheduleLibraryLine(listOf(entry(1, "2026-10-01").copy(dataIssue = CalendarDataIssue.NoServer))),
        )
        assertEquals("媒体库暂无此剧", scheduleLibraryLine(listOf(entry(1, "2026-10-01"))))
    }

    @Test
    fun sharing_starts_at_the_latest_broadcast_and_keeps_to_public_facts() {
        val days =
            listOf(
                CalendarDay("2026-09-28", listOf(entry(38, "2026-09-28"))),
                CalendarDay("2026-10-01", listOf(entry(39, "2026-10-01"))),
                CalendarDay("2026-10-02", (40..44).map { entry(it, "2026-10-02") }),
                CalendarDay("2026-10-04", listOf(entry(45, "2026-10-04", status = LibraryStatus.Unaired))),
            ).map { day ->
                day.copy(
                    entries =
                        day.entries.map {
                            it.copy(sources = listOf(CalendarSource("a", "Rex", status = it.status)))
                        },
                )
            }

        val text = scheduleShareText("兰香如故", days, today = "2026-10-03", link = "https://www.themoviedb.org/tv/7")

        assertEquals(
            listOf(
                "《兰香如故》播出日历",
                "第 1 季 · 更新至第 44 集 · 待播 1 集",
                "10月2日 周五 · 第 40-44 集",
                "10月4日 周日 · 第 45 集",
                "https://www.themoviedb.org/tv/7",
            ),
            text.lines(),
        )
        assertFalse("Rex" in text)

        val long =
            (1..20).map { day ->
                val date = "2026-11-${day.toString().padStart(2, '0')}"
                CalendarDay(date, listOf(entry(day, date)))
            }
        val trimmed = scheduleShareText("长剧", long, today = "2026-10-03", link = null, maxDays = 3).lines()
        assertEquals("11月1日 周日 · 第 1 集", trimmed[2])
        assertEquals("……", trimmed.last())
    }

    private fun entry(
        episode: Int,
        date: String,
        season: Int = 1,
        status: LibraryStatus = LibraryStatus.Missing,
    ) = CalendarEntry(
        episode =
            AiringEpisode(
                showTmdbId = 7,
                showTitle = "兰香如故",
                posterPath = null,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = null,
                airDate = date,
                origin = ShowOrigin.Domestic,
            ),
        status = status,
    )

    private fun movie(date: String) =
        CalendarEntry(
            episode =
                AiringEpisode(
                    showTmdbId = 9,
                    showTitle = "电影",
                    posterPath = null,
                    seasonNumber = 0,
                    episodeNumber = 0,
                    episodeTitle = null,
                    airDate = date,
                    origin = ShowOrigin.Domestic,
                    kind = AiringKind.Movie,
                ),
            status = LibraryStatus.Unaired,
        )
}
