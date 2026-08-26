package com.yfuse.core.data

import com.yfuse.core.model.ShowOrigin
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbRepositoryTest {
    @Test
    fun home_interleaves_domestic_content_into_all_existing_rows() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        val originCountries = request.url.parameters["with_origin_country"]
                        val domestic = originCountries?.split('|')?.contains("CN") == true
                        if (domestic) {
                            assertEquals("zh", request.url.parameters["with_original_language"])
                        }
                        val tv = path.contains("/tv")
                        val sortBy = request.url.parameters["sort_by"]
                        val latest =
                            sortBy == "primary_release_date.desc" ||
                                sortBy == "first_air_date.desc"
                        val upcoming =
                            !latest &&
                                (
                                    request.url.parameters["primary_release_date.gte"] != null ||
                                        request.url.parameters["first_air_date.gte"] != null
                                )
                        if (upcoming || latest) {
                            assertEquals(null, request.url.parameters["vote_count.gte"])
                            if (domestic) assertEquals("CN|HK|TW", originCountries)
                        }
                        val now =
                            request.url.parameters["primary_release_date.lte"] != null ||
                                request.url.parameters["first_air_date.lte"] != null ||
                                path.endsWith("/movie/now_playing") ||
                                path.endsWith("/tv/airing_today")
                        val phase =
                            if (latest) {
                                4
                            } else if (upcoming) {
                                3
                            } else if (now) {
                                2
                            } else {
                                1
                            }
                        val id = (if (domestic) 100 else 200) + phase * 10 + if (tv) 1 else 0
                        val titleKey = if (tv) "name" else "title"
                        val dateKey = if (tv) "first_air_date" else "release_date"
                        val date =
                            when {
                                latest ->
                                    request.url.parameters[
                                        if (tv) "first_air_date.lte" else "primary_release_date.lte",
                                    ]!!
                                upcoming -> "2027-01-01"
                                else -> "2026-01-01"
                            }
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
            assertTrue(rows.getValue("最新上线").items.any { it.title.startsWith("国产") })
            assertTrue(rows.getValue("正在上映").items.any { it.title.startsWith("国产") })
            assertTrue(rows.getValue("即将上映").items.any { it.title.startsWith("国产") })
        }

    @Test
    fun home_filters_talk_documentary_and_untrusted_domestic_metadata() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val domestic =
                            request.url.parameters["with_origin_country"]
                                ?.split('|')
                                ?.contains("CN") == true
                        val path = request.url.encodedPath
                        val tv = path.contains("/tv")
                        val sortBy = request.url.parameters["sort_by"]
                        val latest =
                            sortBy == "primary_release_date.desc" ||
                                sortBy == "first_air_date.desc"
                        val upcoming =
                            !latest &&
                                (
                                    request.url.parameters["primary_release_date.gte"] != null ||
                                        request.url.parameters["first_air_date.gte"] != null
                                )
                        val titleKey = if (tv) "name" else "title"
                        val dateKey = if (tv) "first_air_date" else "release_date"
                        val date =
                            when {
                                latest ->
                                    request.url.parameters[
                                        if (tv) "first_air_date.lte" else "primary_release_date.lte",
                                    ]!!
                                upcoming -> "2027-01-01"
                                else -> "2026-01-01"
                            }
                        val results =
                            if (domestic) {
                                """
                                [
                                    {"id":1,"$titleKey":"可信国产","$dateKey":"$date","poster_path":"/1.jpg",
                                     "vote_count":40,"popularity":20.0,"genre_ids":[18],"original_language":"zh"},
                                    {"id":2,"$titleKey":"百家讲坛","$dateKey":"$date","poster_path":"/2.jpg",
                                     "vote_count":400,"popularity":20.0,"genre_ids":[99],"original_language":"zh"},
                                    {"id":3,"$titleKey":"错误年份条目","$dateKey":"$date","poster_path":"/3.jpg",
                                     "vote_count":1,"popularity":1.0,"genre_ids":[18],"original_language":"zh"}
                                ]
                                """.trimIndent()
                            } else {
                                """
                                [
                                    {"id":4,"$titleKey":"正常内容","$dateKey":"$date","poster_path":"/4.jpg",
                                     "vote_count":50,"popularity":20.0,"genre_ids":[18],"original_language":"en"},
                                    {"id":5,"$titleKey":"深夜脱口秀","$dateKey":"$date","poster_path":"/5.jpg",
                                     "vote_count":500,"popularity":20.0,"genre_ids":[10767],"original_language":"en"}
                                ]
                                """.trimIndent()
                            }
                        json("""{"results":$results}""")
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val rows =
                TmdbRepository(client)
                    .home()
                    .getOrThrow()
                    .rows
                    .associateBy { it.title }
            val establishedTitles =
                listOf("热门", "正在上映")
                    .flatMap { rows.getValue(it).items }
                    .map { it.title }
            val upcomingTitles = rows.getValue("即将上映").items.map { it.title }
            val latestTitles = rows.getValue("最新上线").items.map { it.title }
            val titles = establishedTitles + latestTitles + upcomingTitles

            assertTrue("可信国产" in titles)
            assertTrue("正常内容" in titles)
            assertTrue("百家讲坛" !in titles)
            assertTrue("错误年份条目" !in establishedTitles)
            assertTrue("错误年份条目" in latestTitles)
            assertTrue("错误年份条目" in upcomingTitles)
            assertTrue("深夜脱口秀" !in titles)
        }

    @Test
    fun detail_fills_metadata_cast_and_runtime() =
        runTest {
            val client =
                HttpClient(
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

    /**
     * The bug that emptied 国产 out of the calendar.
     *
     * A Chinese web drama's TMDB season routinely lists every episode by name with no
     * `air_date` on any of them; the only dates on the record are the show-level
     * `next_episode_to_air` / `last_episode_to_air`. The season list was therefore
     * non-empty, the "fall back to last/next" branch never fired, and every undated stub
     * was dropped — so the show contributed no rows at all.
     */
    @Test
    fun a_season_whose_episodes_carry_no_dates_falls_back_to_the_show_record() =
        runTest {
            var seasonRequested = false
            var previewEpisodeNumbers = emptyList<Int>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        when {
                            path.endsWith("/discover/tv") -> {
                                val domestic = request.url.parameters["with_origin_country"] == "CN"
                                if (!domestic) return@MockEngine json("""{"results":[]}""")
                                json(
                                    // Brand-new TMDB entries often receive episode dates before artwork.
                                    // Calendar discovery must not discard the schedule for that reason.
                                    """{"results":[{"id":7,"name":"国产日更剧",""" +
                                        """"first_air_date":"2026-07-01","vote_count":40,""" +
                                        """"original_language":"zh","popularity":90.0}]}""",
                                )
                            }
                            path.endsWith("/discover/movie") -> json("""{"results":[]}""")
                            path.endsWith("/tv/7") ->
                                json(
                                    """{"id":7,"name":"国产日更剧","poster_path":"/p.jpg",""" +
                                        """"last_episode_to_air":{"air_date":"2026-08-01","season_number":1,""" +
                                        """"episode_number":12,"name":"第 12 集"},""" +
                                        """"next_episode_to_air":{"air_date":"2026-08-02","season_number":1,""" +
                                        """"episode_number":13,"name":"第 13 集"}}""",
                                )
                            // Named episodes, not one of them dated — which is the whole point.
                            path.contains("/season/") -> {
                                seasonRequested = true
                                json(
                                    """{"season_number":1,"episodes":[""" +
                                        """{"episode_number":1,"name":"第 1 集"},""" +
                                        """{"episode_number":2,"name":"第 2 集"}]}""",
                                )
                            }
                            else -> json("""{"results":[]}""")
                        }
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val result =
                TmdbRepository(client)
                    .airingCalendar(
                        fromDate = "2026-07-28",
                        toDate = "2026-08-05",
                        onPreview = { preview ->
                            assertFalse(seasonRequested)
                            previewEpisodeNumbers = preview.map { it.episodeNumber }
                        },
                    )

            assertTrue(result.isSuccess, result.toString())
            val episodes = result.getOrThrow()
            assertEquals(listOf(12, 13), episodes.map { it.episodeNumber })
            assertEquals(listOf(12, 13), previewEpisodeNumbers)
            assertTrue(episodes.all { it.showTitle == "国产日更剧" })
        }

    @Test
    fun the_calendar_carries_film_releases_alongside_broadcasts() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        when {
                            path.endsWith("/discover/movie") -> {
                                val domestic = request.url.parameters["with_origin_country"] == "CN"
                                json(
                                    if (domestic) {
                                        """{"results":[{"id":50,"title":"国产电影","poster_path":"/m.jpg",""" +
                                            """"release_date":"2026-08-01","vote_count":30,""" +
                                            """"original_language":"zh","popularity":40.0}]}"""
                                    } else {
                                        """{"results":[{"id":60,"title":"外国电影","poster_path":"/n.jpg",""" +
                                            """"release_date":"2026-08-03","vote_count":30,""" +
                                            """"original_language":"en","popularity":40.0}]}"""
                                    },
                                )
                            }
                            else -> json("""{"results":[]}""")
                        }
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val result =
                TmdbRepository(client)
                    .airingCalendar(fromDate = "2026-07-28", toDate = "2026-08-05")

            assertTrue(result.isSuccess, result.toString())
            val films = result.getOrThrow()
            assertEquals(listOf("国产电影", "外国电影"), films.map { it.showTitle })
            assertTrue(films.all { it.isMovie })
            assertEquals(listOf(ShowOrigin.Domestic, ShowOrigin.Foreign), films.map { it.origin })
            // A film has no coordinate, so its key is the film itself.
            assertEquals("tmdb-movie:50", films.first().mediaKey)
            assertEquals("电影上映", films.first().episodeLabel)
        }

    @Test
    fun a_series_detail_calendar_queries_the_exact_show_and_expands_its_current_season() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        paths += path
                        when {
                            path.endsWith("/tv/88") ->
                                json(
                                    """{"id":88,"name":"精确查询的剧","poster_path":"/series.jpg",""" +
                                        """"origin_country":["CN"],"original_language":"zh","number_of_seasons":2,""" +
                                        """"last_episode_to_air":{"air_date":"2026-08-24","season_number":2,""" +
                                        """"episode_number":2,"name":"第二集"},"next_episode_to_air":{""" +
                                        """"air_date":"2026-08-26",""" +
                                        """"season_number":2,"episode_number":3,"name":"第三集"}}""",
                                )
                            path.endsWith("/tv/88/season/2") ->
                                json(
                                    """{"season_number":2,"episodes":[""" +
                                        """{"air_date":"2026-08-22","episode_number":1,"name":"第一集"},""" +
                                        """{"air_date":"2026-08-24","episode_number":2,"name":"第二集"},""" +
                                        """{"air_date":"2026-08-26","episode_number":3,"name":"第三集"}]}""",
                                )
                            else -> error("Unexpected TMDB path: $path")
                        }
                    },
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val result = TmdbRepository(client).seriesAiringCalendar(88, fallbackTitle = "后备标题")

            assertTrue(result.isSuccess, result.toString())
            val episodes = result.getOrThrow()
            assertEquals(listOf(1, 2, 3), episodes.map { it.episodeNumber })
            assertTrue(episodes.all { it.seasonNumber == 2 })
            assertTrue(episodes.all { it.showTitle == "精确查询的剧" })
            assertTrue(episodes.all { it.origin == ShowOrigin.Domestic })
            assertEquals(listOf("/3/tv/88", "/3/tv/88/season/2"), paths)
        }
}
