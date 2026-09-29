package com.yfuse.core.data

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
import kotlin.test.assertIs
import kotlin.test.assertNull

class TmdbTitleSearchTest {
    @Test
    fun a_film_search_asks_for_the_release_year_and_keeps_both_names() =
        runTest {
            var seen: HttpRequestData? = null
            val repository =
                repository { request ->
                    seen = request
                    json(
                        """{"results":[{"id":603,"title":"黑客帝国","original_title":"The Matrix",""" +
                            """"release_date":"1999-03-31","poster_path":"/m.jpg","vote_average":8.2},""" +
                            """{"id":9,"title":"","original_title":"Nameless"}]}""",
                    )
                }

            val results = repository.searchTitles("The Matrix", "movie", year = 1999).getOrThrow()

            val request = assertIs<HttpRequestData>(seen)
            assertEquals("/3/search/movie", request.url.encodedPath)
            assertEquals("The Matrix", request.url.parameters["query"])
            assertEquals("1999", request.url.parameters["primary_release_year"])
            assertEquals("zh-CN", request.url.parameters["language"])
            assertEquals("false", request.url.parameters["include_adult"])
            val matrix = results.single()
            assertEquals("黑客帝国", matrix.title)
            assertEquals("The Matrix", matrix.originalTitle)
            assertEquals(1999, matrix.year)
            assertEquals("movie", matrix.mediaType)
        }

    @Test
    fun a_show_search_narrows_by_first_air_year_and_reads_show_fields() =
        runTest {
            var seen: HttpRequestData? = null
            val repository =
                repository { request ->
                    seen = request
                    json(
                        """{"results":[{"id":1396,"name":"绝命毒师","original_name":"Breaking Bad","first_air_date":"2008-01-20"}]}""",
                    )
                }

            val show = repository.searchTitles("Breaking Bad", "tv", year = 2008).getOrThrow().single()

            assertEquals("2008", assertIs<HttpRequestData>(seen).url.parameters["first_air_date_year"])
            assertNull(seen?.url?.parameters?.get("primary_release_year"))
            assertEquals("Breaking Bad", show.originalTitle)
            assertEquals(2008, show.year)
        }

    @Test
    fun alternative_titles_read_either_list_name() =
        runTest {
            val repository =
                repository { request ->
                    if (request.url.encodedPath.startsWith("/3/tv/")) {
                        json("""{"id":209867,"results":[{"iso_3166_1":"JP","title":"Sousou no Frieren"}]}""")
                    } else {
                        json("""{"id":535167,"titles":[{"iso_3166_1":"US","title":"The Wandering Earth"}]}""")
                    }
                }

            assertEquals(listOf("Sousou no Frieren"), repository.alternativeTitles("tv", 209867).getOrThrow())
            assertEquals(listOf("The Wandering Earth"), repository.alternativeTitles("movie", 535167).getOrThrow())
        }

    @Test
    fun a_refused_token_is_a_failure_not_an_empty_answer() =
        runTest {
            val repository = repository { respond("""{"status_code":7}""", HttpStatusCode.Unauthorized) }

            val failure = repository.searchTitles("Dune", "movie").exceptionOrNull()

            assertEquals(
                TmdbRecommendationFailure.AUTHORIZATION,
                assertIs<TmdbRecommendationException>(failure).failure,
            )
        }

    private fun TestScope.repository(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        TmdbRepository(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler(handler)
                    },
                ),
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )
}
