package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A title is only a guess: a 短剧 without a TMDB entry often shares its name with another show,
 * so a guessed identity must not stand for good the way the viewer's own choice does.
 */
class CalendarIdentityGuessTest {
    private val settings = MapSettings()
    private val schedules =
        OfficialAiringScheduleCatalog(
            client = HttpClient(MockEngine { error("No publication in this test") }),
            settings = settings,
        )
    private val resolver = CalendarIdentityResolver(schedules, settings)

    private fun series(
        id: String,
        year: Int? = null,
        tmdb: String? = null,
    ) = LibrarySeriesIdentity(
        itemId = id,
        title = "师兄太稳健",
        year = year,
        providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(),
    )

    @Test
    fun candidates_carry_the_year_the_show_premiered() {
        assertEquals(2026, schedules.identityCandidates("师兄太稳健").first().year)
    }

    @Test
    fun a_title_guess_yields_once_the_server_names_the_series() =
        runTest {
            assertEquals(272938, resolver.resolve(series("guessed", year = 2026), SERVER).getOrThrow())
            assertFalse(resolver.chosenByViewer(SERVER, "guessed", 272938))

            assertEquals(4242, resolver.resolve(series("guessed", tmdb = "4242"), SERVER).getOrThrow())
        }

    @Test
    fun a_series_from_another_year_is_not_guessed() =
        runTest {
            val result = resolver.resolve(series("older", year = 2019), SERVER)

            val ambiguous = assertIs<CalendarIdentityAmbiguousException>(result.exceptionOrNull())
            assertEquals(listOf(272938), ambiguous.candidates.map { it.tmdbId })
        }

    @Test
    fun a_guess_the_premiere_year_now_contradicts_is_dropped() =
        runTest {
            resolver.rememberTitleMatch(SERVER, "older", 272938)

            assertTrue(resolver.resolve(series("older", year = 2019), SERVER).isFailure)
            assertNull(resolver.mappedSeriesItemId(SERVER, 272938))
        }

    @Test
    fun the_viewer_choice_outranks_the_server_id() =
        runTest {
            resolver.remember(SERVER, "picked", 272938)

            assertEquals(272938, resolver.resolve(series("picked", tmdb = "4242"), SERVER).getOrThrow())
            assertTrue(resolver.chosenByViewer(SERVER, "picked", 272938))
        }

    @Test
    fun a_show_the_viewer_gave_another_series_is_not_guessed() =
        runTest {
            resolver.remember(SERVER, "picked", 272938)

            assertTrue(resolver.resolve(series("same-name"), SERVER).isFailure)
            assertEquals("picked", resolver.mappedSeriesItemId(SERVER, 272938))
        }

    @Test
    fun years_agree_within_one_or_when_unknown() {
        assertTrue(identityYearsAgree(2026, 2025))
        assertTrue(identityYearsAgree(null, 2026))
        assertTrue(identityYearsAgree(2019, null))
        assertFalse(identityYearsAgree(2019, 2026))
    }

    private companion object {
        const val SERVER = "server"
    }
}
