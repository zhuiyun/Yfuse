package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarOriginVisibilityTest {
    @Test
    fun default_calendar_keeps_unfollowed_domestic_foreign_and_unknown_rows() {
        val state = mixedCalendar()

        assertEquals(CalendarFilter.All, state.filter)
        assertEquals(listOf("国产新剧", "国外新剧", "地区未确认"), state.visibleTitles())
    }

    @Test
    fun origin_filters_select_known_regions_without_misclassifying_unknown_rows() {
        val state = mixedCalendar()

        assertEquals(listOf("国产新剧"), state.copy(filter = CalendarFilter.Domestic).visibleTitles())
        assertEquals(listOf("国外新剧"), state.copy(filter = CalendarFilter.Foreign).visibleTitles())
        assertEquals(emptyList(), state.copy(filter = CalendarFilter.Mine).visibleTitles())
        assertEquals(listOf("国产新剧", "国外新剧", "地区未确认"), state.copy(filter = CalendarFilter.All).visibleTitles())
    }

    private fun mixedCalendar() =
        CalendarState(
            loading = false,
            today = "2026-11-05",
            days =
                listOf(
                    CalendarDay(
                        "2026-11-05",
                        listOf(
                            entry(1, "国产新剧", ShowOrigin.Domestic),
                            entry(2, "国外新剧", ShowOrigin.Foreign),
                            entry(3, "地区未确认", ShowOrigin.Unknown),
                        ),
                    ),
                ),
        )

    private fun entry(
        id: Int,
        title: String,
        origin: ShowOrigin,
    ) = CalendarEntry(
        episode = AiringEpisode(id, title, null, 1, 1, null, "2026-11-05", origin),
        status = LibraryStatus.Unknown,
        followed = false,
        discoveryOnly = true,
    )

    private fun CalendarState.visibleTitles() = visibleDays.flatMap { day -> day.entries.map { it.episode.showTitle } }
}
