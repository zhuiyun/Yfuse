package com.yfuse.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TheOtherDbApiTest {
    @Test
    fun anonymous_post_uses_exact_identity_and_reads_all_pages() =
        runTest {
            var calls = 0
            val engine =
                MockEngine { request ->
                    calls++
                    assertEquals("https://playback.theotherdb.org/api/metadata", request.url.toString())
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("tmdb_id_tv", body.getValue("platform_type").jsonPrimitive.content)
                    assertEquals("226045", body.getValue("platform_value").jsonPrimitive.content)
                    assertEquals("1", body.getValue("season_number").jsonPrimitive.content)
                    assertEquals("2", body.getValue("episode_number").jsonPrimitive.content)
                    assertEquals(calls.toString(), body.getValue("page").jsonPrimitive.content)
                    respond(
                        """{"has_more":${calls == 1},"items":[{"version_id":$calls,"version_runtime":1300,"video_type":"tv","unknown":"ignored","marker":[{"marker_type":"intro","time_start":0,"time_end":27}]}]}""",
                    )
                }
            createTheOtherDbClient(engine).use { client ->
                val versions = TheOtherDbApi(client).versions(TheOtherDbQuery(226045, 1, 2))
                assertEquals(listOf(1L, 2L), versions.map { it.id })
                assertEquals(
                    27.0,
                    versions
                        .first()
                        .markers
                        .first()
                        .end,
                )
            }
        }

    @Test
    fun movies_omit_season_and_episode() =
        runTest {
            val engine =
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("tmdb_id_movie", body.getValue("platform_type").jsonPrimitive.content)
                    assertNull(body["season_number"])
                    assertNull(body["episode_number"])
                    respond("""{"has_more":false,"items":[]}""")
                }
            createTheOtherDbClient(engine).use { client ->
                assertEquals(emptyList(), TheOtherDbApi(client).versions(TheOtherDbQuery(603)))
            }
        }

    @Test
    fun error_or_truncated_pagination_is_not_treated_as_no_data() =
        runTest {
            val failed = MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) }
            createTheOtherDbClient(failed).use { client ->
                assertFailsWith<Exception> { TheOtherDbApi(client).versions(TheOtherDbQuery(603)) }
            }
            val endless = MockEngine { respond("""{"has_more":true,"items":[]}""") }
            createTheOtherDbClient(endless).use { client ->
                assertFailsWith<IllegalStateException> { TheOtherDbApi(client).versions(TheOtherDbQuery(603)) }
            }
        }

    @Test
    fun response_without_the_documented_page_shape_is_an_error() =
        runTest {
            val engine = MockEngine { respond("{}") }
            createTheOtherDbClient(engine).use { client ->
                assertFailsWith<Exception> { TheOtherDbApi(client).versions(TheOtherDbQuery(603)) }
            }
        }
}
