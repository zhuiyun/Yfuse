package com.yfuse.core.trakt

import com.russhwolf.settings.MapSettings
import com.yfuse.core.personal.PersonalLibraryRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalTraktImportSinkTest {
    @Test
    fun longMovieAndEpisodeTitlesAreImportedWithinThePersonalLibraryBudget() =
        runTest {
            val personal = PersonalLibraryRepository(MapSettings())
            val sink = PersonalTraktImportSink(personal)
            val movie =
                TraktListItem("movie", movie = TraktTitle("电影".repeat(200), ids = TraktIds(tmdb = 603)))
            val episode =
                TraktListItem(
                    "episode",
                    show = TraktTitle("剧集".repeat(100), ids = TraktIds(tmdb = 100)),
                    episode = TraktEpisode("分集".repeat(200), 2, 3),
                    watchedAt = "2026-10-08T00:00:00Z",
                )

            assertTrue(sink.importWatchlist(movie))
            assertTrue(sink.importHistory(episode))
            assertEquals(
                240,
                personal.state.value.watchLater
                    .single()
                    .media.title.length,
            )
            assertEquals(
                240,
                personal.state.value.history
                    .single()
                    .media.title.length,
            )
            assertEquals(
                "tmdb:100/s2e3",
                personal.state.value.history
                    .single()
                    .media.mediaKey,
            )
            assertNull(personal.state.value.error)
        }
}
