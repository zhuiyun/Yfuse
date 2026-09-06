package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A remembered library identity survives the round in which the rich series catalog fails.
 *
 * The provider-only index carries that round with an empty catalog, and judging every
 * remembered mapping against an empty catalog used to forget them all — including the ones
 * the user picked by hand on the detail page.
 */
class CalendarIdentityRetentionTest {
    private val server = SavedServer("server", "https://library.example", "家庭影院", "user", "用户", "token")

    @Test
    fun a_failed_series_catalog_keeps_the_remembered_identity_and_still_uses_it() =
        runTest {
            val external = client { respond("", HttpStatusCode.ServiceUnavailable) }
            val episodeRequests = mutableListOf<String>()
            val library =
                client { request ->
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("/Episodes") -> {
                            episodeRequests += path
                            json(
                                """{"Items":[{"Id":"episode-13","Name":"第13集","Type":"Episode","IndexNumber":13,"ParentIndexNumber":1}]}""",
                            )
                        }
                        // The single-show fast path finds nothing by provider id: this library
                        // stores the show without a TMDB id, which is exactly when the remembered
                        // mapping is the only way to it.
                        request.url.parameters["AnyProviderIdEquals"] != null -> json("""{"Items":[]}""")
                        // The rich catalog fails; the provider-only index answers with nothing.
                        request.url.parameters["Fields"]?.contains("DateCreated") == true ->
                            respond("", HttpStatusCode.InternalServerError)
                        else -> json("""{"Items":[]}""")
                    }
                }
            try {
                val settings = MapSettings()
                val registry = ServerRegistry(settings, TestSecureStore()).apply { addOrUpdate(server) }
                val schedules = OfficialAiringScheduleCatalog(external, settings)
                val resolver = CalendarIdentityResolver(schedules, settings)
                resolver.remember(server.id, "series-item", 272938)
                val repository =
                    AiringCalendarRepository(
                        EmbyRepository(library),
                        registry,
                        schedules,
                        resolver,
                        CalendarFollowStore(settings),
                    )

                val days = repository.calendar(today = "2026-08-25").getOrThrow()

                assertEquals("series-item", resolver.mappedSeriesItemId(server.id, 272938))
                assertEquals(listOf("/Shows/series-item/Episodes"), episodeRequests)
                val thirteenth =
                    days
                        .flatMap(CalendarDay::entries)
                        .single { it.episode.showTmdbId == 272938 && it.episode.episodeNumber == 13 }
                assertEquals(LibraryStatus.Available, thirteenth.status)
                assertEquals("episode-13", thirteenth.itemId)
            } finally {
                external.close()
                library.close()
            }
        }

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler(handler)
                },
            ),
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}
