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
    fun home_interleaves_domestic_content_into_all_existing_rows() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val domestic = request.url.parameters["with_origin_country"] == "CN"
                if (domestic) {
                    assertEquals("CN", request.url.parameters["with_origin_country"])
                    assertEquals("zh", request.url.parameters["with_original_language"])
                }
                val tv = path.contains("/tv")
                val upcoming = request.url.parameters["primary_release_date.gte"] != null ||
                    request.url.parameters["first_air_date.gte"] != null ||
                    path.endsWith("/movie/upcoming")
                val now = request.url.parameters["primary_release_date.lte"] != null ||
                    request.url.parameters["first_air_date.lte"] != null ||
                    path.endsWith("/movie/now_playing") ||
                    path.endsWith("/tv/airing_today")
                val phase = if (upcoming) 3 else if (now) 2 else 1
                val id = (if (domestic) 100 else 200) + phase * 10 + if (tv) 1 else 0
                val titleKey = if (tv) "name" else "title"
                val dateKey = if (tv) "first_air_date" else "release_date"
                val date = if (upcoming) "2027-01-01" else "2026-01-01"
                json(
                    """{"results":[{"id":$id,"$titleKey":""" +
                        """"${if (domestic) "国产" else "全球"}-$phase-${if (tv) "剧" else "影"}",""" +
                        """"$dateKey":"$date","poster_path":"/$id.jpg","vote_count":20,""" +
                        """"popularity":12.0,"genre_ids":[18],"original_language":""" +
                        """"${if (domestic) "zh" else "en"}"}]}""",
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val result = TmdbRepository(client).home()

        assertTrue(result.isSuccess, result.toString())
        val rows = result.getOrThrow().rows.associateBy { it.title }
        assertTrue(rows.getValue("热门").items.any { it.title.startsWith("国产") })
        assertTrue(rows.getValue("正在上映").items.any { it.title.startsWith("国产") })
        assertTrue(rows.getValue("即将上映").items.any { it.title.startsWith("国产") })
    }

    @Test
    fun home_filters_talk_documentary_and_untrusted_domestic_metadata() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                val domestic = request.url.parameters["with_origin_country"] == "CN"
                val path = request.url.encodedPath
                val tv = path.contains("/tv")
                val upcoming = request.url.parameters["primary_release_date.gte"] != null ||
                    request.url.parameters["first_air_date.gte"] != null ||
                    path.endsWith("/movie/upcoming")
                val titleKey = if (tv) "name" else "title"
                val dateKey = if (tv) "first_air_date" else "release_date"
                val date = if (upcoming) "2027-01-01" else "2026-01-01"
                val results = if (domestic) {
                    """[
                        {"id":1,"$titleKey":"可信国产","$dateKey":"$date","poster_path":"/1.jpg",
                         "vote_count":40,"popularity":20.0,"genre_ids":[18],"original_language":"zh"},
                        {"id":2,"$titleKey":"百家讲坛","$dateKey":"$date","poster_path":"/2.jpg",
                         "vote_count":400,"popularity":20.0,"genre_ids":[99],"original_language":"zh"},
                        {"id":3,"$titleKey":"错误年份条目","$dateKey":"$date","poster_path":"/3.jpg",
                         "vote_count":1,"popularity":1.0,"genre_ids":[18],"original_language":"zh"}
                    ]""".trimIndent()
                } else {
                    """[
                        {"id":4,"$titleKey":"正常内容","$dateKey":"$date","poster_path":"/4.jpg",
                         "vote_count":50,"popularity":20.0,"genre_ids":[18],"original_language":"en"},
                        {"id":5,"$titleKey":"深夜脱口秀","$dateKey":"$date","poster_path":"/5.jpg",
                         "vote_count":500,"popularity":20.0,"genre_ids":[10767],"original_language":"en"}
                    ]""".trimIndent()
                }
                json("""{"results":$results}""")
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val rows = TmdbRepository(client).home().getOrThrow().rows
        val titles = rows.flatMap { it.items }.map { it.title }

        assertTrue("可信国产" in titles)
        assertTrue("正常内容" in titles)
        assertTrue("百家讲坛" !in titles)
        assertTrue("错误年份条目" !in titles)
        assertTrue("深夜脱口秀" !in titles)
    }

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
