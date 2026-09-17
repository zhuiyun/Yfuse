package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.createEmbyClient
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbySearchServiceTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun the_first_relevance_page_asks_for_a_wider_window_and_ranks_the_exact_title_first() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json(
                        """
                        {"Items":[
                            {"Id":"b","Name":"Matrix Reloaded","Type":"Movie"},
                            {"Id":"a","Name":"Matrix","Type":"Movie"}
                        ],"TotalRecordCount":2}
                        """.trimIndent(),
                    )
                }
            try {
                val page =
                    EmbySearchService(client)
                        .searchPage(server, "Matrix", startIndex = 0, limit = 20, filter = MediaSearchFilter())
                        .getOrThrow()

                val request = requireNotNull(seen)
                assertEquals("/Users/u1/Items", request.url.encodedPath)
                assertEquals("Matrix", request.url.parameters["SearchTerm"])
                assertEquals("50", request.url.parameters["Limit"])
                assertEquals("true", request.url.parameters["Recursive"])
                assertEquals("Movie,Series", request.url.parameters["IncludeItemTypes"])
                assertNull(request.url.parameters["StartIndex"])
                assertNull(request.url.parameters["SortBy"])
                assertEquals(listOf("a", "b"), page.items.map { it.id })
                assertEquals(2, page.totalCount)
                assertEquals(0, page.startIndex)
                assertEquals(2, page.nextStartIndex)
            } finally {
                client.close()
            }
        }

    @Test
    fun an_explicit_sort_keeps_the_server_order_and_pages_with_the_requested_limit() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json("""{"Items":[{"Id":"b","Name":"Zeta"},{"Id":"a","Name":"Alpha"}],"TotalRecordCount":42}""")
                }
            try {
                val filter = MediaSearchFilter(sortBy = "SortName", descending = true, played = false)
                val page =
                    EmbySearchService(client)
                        .searchPage(server, "a", startIndex = 20, limit = 20, filter = filter)
                        .getOrThrow()

                val request = requireNotNull(seen)
                assertEquals("20", request.url.parameters["Limit"])
                assertEquals("20", request.url.parameters["StartIndex"])
                assertEquals("SortName", request.url.parameters["SortBy"])
                assertEquals("Descending", request.url.parameters["SortOrder"])
                assertEquals("false", request.url.parameters["IsPlayed"])
                assertEquals(listOf("b", "a"), page.items.map { it.id })
                assertEquals(20, page.startIndex)
            } finally {
                client.close()
            }
        }

    @Test
    fun an_empty_exact_match_falls_back_to_shorter_terms_that_still_contain_the_query() =
        runTest {
            val terms = mutableListOf<String>()
            val client =
                client { request ->
                    val term = requireNotNull(request.url.parameters["SearchTerm"])
                    terms += term
                    if (term == "复仇者联盟") {
                        json("""{"Items":[],"TotalRecordCount":0}""")
                    } else {
                        json("""{"Items":[{"Id":"x","Name":"复仇者联盟4"},{"Id":"y","Name":"联盟"}]}""")
                    }
                }
            try {
                val page =
                    EmbySearchService(client)
                        .searchPage(server, "复仇者联盟", startIndex = 0, limit = 20, filter = MediaSearchFilter())
                        .getOrThrow()

                assertEquals("复仇者联盟", terms.first())
                assertEquals(setOf("联盟", "者联盟", "复仇"), terms.drop(1).toSet())
                assertEquals(listOf("x"), page.items.map { it.id })
                assertEquals(1, page.totalCount)
                assertEquals(0, page.startIndex)
            } finally {
                client.close()
            }
        }

    @Test
    fun genres_are_scoped_to_the_library_and_deduplicated_case_insensitively() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json(
                        """{"Items":[{"Id":"1","Name":"Drama"},{"Id":"2","Name":"drama"},""" +
                            """{"Id":"3","Name":" "},{"Id":"4","Name":"Comedy"}]}""",
                    )
                }
            try {
                val genres = EmbySearchService(client).genres(server, parentId = "lib1").getOrThrow()

                val request = requireNotNull(seen)
                assertEquals("/Genres", request.url.encodedPath)
                assertEquals("u1", request.url.parameters["UserId"])
                assertEquals("lib1", request.url.parameters["ParentId"])
                assertEquals(listOf("Drama", "Comedy"), genres)
            } finally {
                client.close()
            }
        }

    @Test
    fun a_rejected_search_maps_to_the_unauthorized_domain_error() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.Unauthorized) }
            try {
                val result = EmbySearchService(client).searchPage(server, "x", 0, 20, MediaSearchFilter())

                assertTrue(result.isFailure)
                assertEquals(EmbyError.Unauthorized, assertIs<EmbyErrorException>(result.exceptionOrNull()).error)
            } finally {
                client.close()
            }
        }

    @Test
    fun an_unavailable_person_index_hides_the_row_instead_of_failing_the_search() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.InternalServerError) }
            try {
                assertEquals(emptyList(), EmbySearchService(client).searchPeople(server, "Keanu", limit = 5))
            } finally {
                client.close()
            }
        }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        createEmbyClient(
            appVersion = "test",
            engine =
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = Dispatchers.Unconfined
                        addHandler(handler)
                    },
                ),
            timeouts = null,
        )
}
