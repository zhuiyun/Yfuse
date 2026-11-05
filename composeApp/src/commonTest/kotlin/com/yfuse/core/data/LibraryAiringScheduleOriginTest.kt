package com.yfuse.core.data

import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.Episode
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryAiringScheduleOriginTest {
    @Test
    fun a_library_date_without_country_metadata_never_implies_foreign_or_domestic_origin() {
        val hint =
            SeriesCalendarLibraryHint(
                showTmdbId = 42,
                server = SavedServer("server", "https://library.example", "媒体库", "user", "用户", "token"),
                seriesItemId = "series",
                episodes =
                    listOf(
                        Episode(
                            id = "episode",
                            name = "第1集",
                            indexNumber = 1,
                            seasonNumber = 1,
                            seasonId = null,
                            overview = null,
                            runtimeMinutes = null,
                            primaryTag = null,
                            playedPercentage = null,
                            resumePositionTicks = null,
                            premiereDate = "2026-11-05",
                        ),
                    ),
                episodesComplete = true,
            )
        for (title in listOf("国产剧中文名", "A Foreign Title")) {
            val row = libraryAiringSchedule(hint, title).single()
            assertEquals(ShowOrigin.Unknown, row.origin)
            assertEquals(AiringScheduleAuthority.Library, row.scheduleAuthority)
            assertEquals("2026-11-05", row.airDate)
        }
    }
}
