package com.yfuse.core.data

import com.yfuse.core.model.TmdbItem
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TmdbRepositoryTest {

    @Test
    fun detail_fills_metadata_cast_and_runtime() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertTrue(request.url.encodedPath.endsWith("/movie/42"))
                assertEquals("credits", request.url.parameters["append_to_response"])
                json(
                    """{"id":42,"title":"完整电影","overview":"完整简介","release_date":"2026-01-02",""" +
                        """"vote_average":8.6,"runtime":128,"tagline":"一句宣传语","status":"Released",""" +
                        """"genres":[{"name":"科幻"},{"name":"剧情"}],"credits":{"cast":[""" +
                        """{"id":1,"name":"演员甲","character":"主角","profile_path":"/p.jpg"}]}}""",
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val repo = TmdbRepository(client)
        val summary = TmdbItem(42, "电影", null, null, null, null, "movie", null)

        val result = repo.detail(summary)

        assertTrue(result.isSuccess, result.toString())
        val detail = result.getOrThrow()
        assertEquals("完整电影", detail.item.title)
        assertEquals("完整简介", detail.item.overview)
        assertEquals(128, detail.runtimeMinutes)
        assertEquals(listOf("科幻", "剧情"), detail.genres)
        assertEquals("演员甲", detail.cast.single().name)
        assertEquals("主角", detail.cast.single().role)
    }
}
