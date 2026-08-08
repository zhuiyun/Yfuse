package com.yfuse.core.data

import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.toUserMessage
import com.yfuse.feature.authRoutes
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbyRepositoryTest {

    private val server = SavedServer("id", "http://host:8096", "zhuiyun", "u1", "zhuiyun", "tok")

    @Test
    fun authenticate_success_returns_server_with_name() = runTest {
        val repo = testRepo { req -> authRoutes(req) }

        val res = repo.authenticate("http://host:8096", "zhuiyun", "123456")

        assertTrue(res.isSuccess, res.toString())
        val s = res.getOrThrow()
        assertEquals("tok", s.accessToken)
        assertEquals("u1", s.userId)
        assertEquals("zhuiyun", s.serverName)
        assertEquals("http://host:8096#u1", s.toSavedServer().id)
    }

    @Test
    fun authenticate_401_returns_unauthorized() = runTest {
        val repo = testRepo { respond(content = "", status = HttpStatusCode.Unauthorized) }

        val res = repo.authenticate("http://host:8096", "x", "y")

        assertTrue(res.isFailure)
        assertEquals(EmbyError.Unauthorized, (res.exceptionOrNull() as EmbyErrorException).error)
    }

    @Test
    fun libraries_parses_items() = runTest {
        val repo = testRepo {
            json(
                """{"Items":[{"Id":"1","Name":"电影","CollectionType":"movies"},""" +
                    """{"Id":"2","Name":"综艺","CollectionType":"tvshows"}]}""",
            )
        }

        val res = repo.libraries(server)

        assertTrue(res.isSuccess, res.toString())
        assertEquals(2, res.getOrThrow().size)
        assertEquals("电影", res.getOrThrow().first().name)
        assertEquals("movies", res.getOrThrow().first().collectionType)
    }

    @Test
    fun libraries_network_failure_maps_to_network_error() = runTest {
        val repo = testRepo { throw IOException("connection closed") }

        val res = repo.libraries(server)

        assertTrue(res.isFailure)
        assertEquals(EmbyError.Network, (res.exceptionOrNull() as EmbyErrorException).error)
    }

    @Test
    fun homeContent_aggregates_resume_latest_and_featured() = runTest {
        val repo = testRepo {
            if (it.url.encodedPath.endsWith("/Items/Counts")) {
                assertEquals("u1", it.url.parameters["UserId"])
            }
            homeRoutes(it)
        }

        val res = repo.homeContent(server)

        assertTrue(res.isSuccess, res.toString())
        val content = res.getOrThrow()
        // resume episode maps to its series title + poster
        assertEquals(1, content.resume.size)
        assertEquals("某剧", content.resume.first().title)
        assertEquals("s1", content.resume.first().posterItemId)
        // latest row present
        assertTrue(content.rows.any { it.libraryId == FAVORITES_COLLECTION_ID })
        assertTrue(content.rows.any { it.libraryId == WATCH_LATER_COLLECTION_ID })
        assertEquals(
            "某电影",
            content.rows.first { it.libraryId == "lib1" }.items.first().title,
        )
        // featured only includes items with a backdrop (the movie, not the episode)
        assertEquals(1, content.featured.size)
        assertEquals("某电影", content.featured.first().title)
        assertEquals(42, content.counts?.movieCount)
        assertEquals(7, content.counts?.seriesCount)
    }

    @Test
    fun homeContent_keeps_content_when_item_counts_are_unavailable() = runTest {
        val repo = testRepo {
            if (it.url.encodedPath.endsWith("/Items/Counts")) {
                respond(content = "", status = HttpStatusCode.InternalServerError)
            } else {
                homeRoutes(it)
            }
        }

        val result = repo.homeContent(server)

        assertTrue(result.isSuccess, result.toString())
        assertTrue(result.getOrThrow().rows.isNotEmpty())
        assertEquals(null, result.getOrThrow().counts)
    }

    @Test
    fun libraryItems_parses_movies() = runTest {
        val repo = testRepo {
            json("""{"Items":[{"Id":"m1","Name":"电影A","Type":"Movie","ProductionYear":2026,"ImageTags":{"Primary":"t"}}]}""")
        }

        val res = repo.libraryItems(server, "lib1")

        assertTrue(res.isSuccess, res.toString())
        assertEquals(1, res.getOrThrow().items.size)
        assertEquals("电影A", res.getOrThrow().items.first().title)
    }

    @Test
    fun libraryItems_pushes_sort_genre_and_paging_to_the_server() = runTest {
        val repo = testRepo { request ->
            assertEquals("ProductionYear,PremiereDate", request.url.parameters["SortBy"])
            assertEquals("Descending", request.url.parameters["SortOrder"])
            assertEquals("科幻", request.url.parameters["Genres"])
            assertEquals("60", request.url.parameters["StartIndex"])
            assertEquals("60", request.url.parameters["Limit"])
            json(
                """{"Items":[{"Id":"m2","Name":"电影B","Type":"Movie",""" +
                    """"ImageTags":{"Primary":"t"}}],"TotalRecordCount":300}""",
            )
        }

        val res = repo.libraryItems(
            server = server,
            libraryId = "lib1",
            sort = LibrarySort.Year,
            genre = "科幻",
            startIndex = 60,
            limit = 60,
        )

        assertTrue(res.isSuccess, res.toString())
        assertEquals(300, res.getOrThrow().totalCount)
        assertEquals(60, res.getOrThrow().startIndex)
    }

    @Test
    fun libraryItems_totalCount_never_undercounts_what_is_already_loaded() = runTest {
        // A server that omits TotalRecordCount would otherwise report a total of zero and
        // stop the grid paging after its first page.
        val repo = testRepo {
            json("""{"Items":[{"Id":"m3","Name":"电影C","Type":"Movie"}]}""")
        }

        val res = repo.libraryItems(server, "lib1", startIndex = 60)

        assertTrue(res.isSuccess, res.toString())
        assertEquals(61, res.getOrThrow().totalCount)
    }

    @Test
    fun libraryGenres_returns_empty_when_the_server_has_no_facet() = runTest {
        val repo = testRepo { respond(content = "", status = HttpStatusCode.NotFound) }

        assertEquals(emptyList(), repo.libraryGenres(server, "lib1"))
    }

    @Test
    fun libraryGenres_parses_names() = runTest {
        val repo = testRepo { request ->
            assertEquals("lib1", request.url.parameters["ParentId"])
            json("""{"Items":[{"Id":"g1","Name":"科幻"},{"Id":"g2","Name":"悬疑"}]}""")
        }

        assertEquals(listOf("科幻", "悬疑"), repo.libraryGenres(server, "lib1"))
    }

    @Test
    fun libraryItems_favorites_uses_user_favorite_filter() = runTest {
        val repo = testRepo { request ->
            assertEquals("IsFavorite", request.url.parameters["Filters"])
            assertEquals("Movie,Series", request.url.parameters["IncludeItemTypes"])
            json(
                """{"Items":[{"Id":"m1","Name":"收藏电影","Type":"Movie",""" +
                    """"ImageTags":{"Primary":"poster"},"UserData":{"IsFavorite":true}}],""" +
                    """"TotalRecordCount":1}""",
            )
        }

        val result = repo.libraryItems(server, FAVORITES_COLLECTION_ID)

        assertTrue(result.isSuccess, result.toString())
        assertEquals("收藏电影", result.getOrThrow().items.single().title)
        assertTrue(result.getOrThrow().items.single().isFavorite)
    }

    @Test
    fun libraryItems_watchLater_reads_the_account_playlist() = runTest {
        val repo = testRepo { request ->
            when {
                request.url.encodedPath.endsWith("/Playlists/p1/Items") -> {
                    assertEquals("u1", request.url.parameters["UserId"])
                    json(
                        """{"Items":[{"Id":"s1","Name":"稍后看的剧","Type":"Series",""" +
                            """"ImageTags":{"Primary":"poster"}}],"TotalRecordCount":1}""",
                    )
                }
                else -> {
                    assertEquals("Playlist", request.url.parameters["IncludeItemTypes"])
                    assertEquals("稍后观看", request.url.parameters["SearchTerm"])
                    json("""{"Items":[{"Id":"p1","Name":"稍后观看","Type":"Playlist"}]}""")
                }
            }
        }

        val result = repo.libraryItems(server, WATCH_LATER_COLLECTION_ID)

        assertTrue(result.isSuccess, result.toString())
        assertEquals("稍后看的剧", result.getOrThrow().items.single().title)
    }

    @Test
    fun search_sends_term_and_parses_results() = runTest {
        val repo = testRepo { request ->
            assertEquals("沙丘", request.url.parameters["SearchTerm"])
            assertEquals("Movie,Series", request.url.parameters["IncludeItemTypes"])
            json(
                """{"Items":[{"Id":"m1","Name":"沙丘2","Type":"Movie","ProductionYear":2024,""" +
                    """"ImageTags":{"Primary":"poster"}}]}""",
            )
        }

        val res = repo.search(server, "沙丘")

        assertTrue(res.isSuccess, res.toString())
        assertEquals("沙丘2", res.getOrThrow().single().title)
        assertEquals("2024", res.getOrThrow().single().subtitle)
        assertEquals(2024, res.getOrThrow().single().year)
    }

    @Test
    fun search_recovers_full_cjk_title_from_suffix_index_match() = runTest {
        val terms = mutableListOf<String>()
        val repo = testRepo { request ->
            val term = request.url.parameters["SearchTerm"].orEmpty()
            terms += term
            if (term == "东宫") {
                json("""{"Items":[{"Id":"m1","Name":"鬼迷东宫","Type":"Series"}]}""")
            } else {
                json("""{"Items":[]}""")
            }
        }

        val result = repo.search(server, "鬼迷东宫")

        assertTrue(result.isSuccess, result.toString())
        assertEquals("鬼迷东宫", result.getOrThrow().single().title)
        assertTrue("鬼迷东宫" in terms)
        assertTrue("东宫" in terms)
    }

    @Test
    fun findByTmdbId_uses_provider_id_and_media_type() = runTest {
        val repo = testRepo { request ->
            assertEquals("tmdb.1234", request.url.parameters["AnyProviderIdEquals"])
            assertEquals("Movie", request.url.parameters["IncludeItemTypes"])
            json(
                """{"Items":[{"Id":"m1","Name":"电影","Type":"Movie","ProviderIds":{"Tmdb":"1234"}}]}""",
            )
        }

        val result = repo.findByTmdbId(server, 1234, "movie")

        assertTrue(result.isSuccess, result.toString())
        assertEquals("m1", result.getOrThrow()?.id)
        assertEquals("1234", result.getOrThrow()?.providerIds?.get("Tmdb"))
    }

    @Test
    fun compareSources_fetches_media_details_when_search_result_omits_them() = runTest {
        val repo = testRepo { request ->
            when {
                request.url.encodedPath.endsWith("/Items/m1") -> json(
                    """{"Id":"m1","Name":"电影A","Type":"Movie","MediaSources":[{""" +
                        """"Size":10737418240,"Bitrate":18000000,""" +
                        """"MediaStreams":[{"Type":"Video","Height":2160,"VideoRange":"HDR10"}]}]}""",
                )
                else -> json("""{"Items":[{"Id":"m1","Name":"电影A","Type":"Movie"}]}""")
            }
        }

        val sources = repo.compareSources(listOf(server), server.id, "电影A")

        assertEquals(1, sources.size)
        assertTrue(sources.single().reachable)
        assertEquals("4K HDR10 · 10.0 GB · 18 Mbps", sources.single().source?.summary)
    }

    @Test
    fun compareSources_resolves_series_to_a_playable_episode() = runTest {
        val repo = testRepo { request ->
            when {
                request.url.encodedPath.contains("/Shows/NextUp") ->
                    json("""{"Items":[{"Id":"e1","Name":"第一集","Type":"Episode"}]}""")
                request.url.encodedPath.endsWith("/Items/e1") ->
                    json(
                        """{"Id":"e1","Name":"第一集","Type":"Episode","MediaSources":[{""" +
                            """"Bitrate":8000000,"MediaStreams":[{"Type":"Video","Height":1080}]}]}""",
                    )
                else -> json("""{"Items":[{"Id":"s1","Name":"某剧","Type":"Series"}]}""")
            }
        }

        val sources = repo.compareSources(listOf(server), server.id, "某剧")

        assertEquals("1080P · 8 Mbps", sources.single().source?.summary)
    }

    @Test
    fun compareSources_uses_each_movies_best_media_source() = runTest {
        val repo = testRepo {
            json(
                """{"Items":[{"Id":"m1","Name":"电影A","Type":"Movie","MediaSources":[""" +
                    """{"Id":"large-1080","Size":107374182400,"Bitrate":80000000,""" +
                    """"MediaStreams":[{"Type":"Video","Width":1920,"Height":1080}]},""" +
                    """{"Id":"compact-4k","Size":21474836480,"Bitrate":20000000,""" +
                    """"MediaStreams":[{"Type":"Video","Width":3840,"Height":1600}]}]}]}""",
            )
        }

        val source = repo.compareSources(
            servers = listOf(server),
            currentServerId = server.id,
            title = "电影A",
            mediaType = "movie",
        ).single().source

        assertEquals(3840, source?.videoWidth)
        assertEquals(1600, source?.videoHeight)
        assertEquals("4K · 20.0 GB · 20 Mbps", source?.summary)
    }

    @Test
    fun compareSources_uses_requested_episode_and_its_best_media_source() = runTest {
        var nextUpRequests = 0
        val repo = testRepo { request ->
            when {
                request.url.encodedPath.contains("/Shows/NextUp") -> {
                    nextUpRequests++
                    json("""{"Items":[]}""")
                }
                request.url.encodedPath.endsWith("/Shows/s1/Episodes") -> {
                    assertEquals("2", request.url.parameters["Season"])
                    json(
                        """{"Items":[""" +
                            """{"Id":"e21","Name":"另一集","Type":"Episode","ParentIndexNumber":2,"IndexNumber":1,""" +
                            """"MediaSources":[{"Id":"other","MediaStreams":[{"Type":"Video","Width":1280,"Height":720}]}]},""" +
                            """{"Id":"e23","Name":"目标集","Type":"Episode","ParentIndexNumber":2,"IndexNumber":3,""" +
                            """"MediaSources":[{"Id":"large-1080","Size":85899345920,"Bitrate":70000000,""" +
                            """"MediaStreams":[{"Type":"Video","Width":1920,"Height":1080}]},""" +
                            """{"Id":"compact-4k","Size":16106127360,"Bitrate":18000000,""" +
                            """"MediaStreams":[{"Type":"Video","Width":3840,"Height":2160}]}]}]}""",
                    )
                }
                else -> json("""{"Items":[{"Id":"s1","Name":"某剧","Type":"Series"}]}""")
            }
        }

        val source = repo.compareSources(
            servers = listOf(server),
            currentServerId = server.id,
            title = "某剧",
            mediaType = "tv",
            seasonNumber = 2,
            episodeNumber = 3,
        ).single().source

        assertEquals(0, nextUpRequests)
        assertEquals(3840, source?.videoWidth)
        assertEquals(2160, source?.videoHeight)
    }

    @Test
    fun compareSources_does_not_offer_a_server_missing_the_requested_episode() = runTest {
        val repo = testRepo { request ->
            if (request.url.encodedPath.endsWith("/Shows/s1/Episodes")) {
                json(
                    """{"Items":[{"Id":"e21","Type":"Episode","ParentIndexNumber":2,""" +
                        """"IndexNumber":1,"MediaSources":[{"Id":"only"}]}]}""",
                )
            } else {
                json("""{"Items":[{"Id":"s1","Name":"某剧","Type":"Series"}]}""")
            }
        }

        val result = repo.compareSources(
            servers = listOf(server),
            currentServerId = server.id,
            title = "某剧",
            mediaType = "tv",
            seasonNumber = 2,
            episodeNumber = 3,
        ).single()

        assertTrue(result.reachable)
        assertEquals("s1", result.itemId)
        assertEquals(null, result.source)
    }

    @Test
    fun compareSources_retries_io_failures_up_to_success() = runTest {
        var requests = 0
        val repo = testRepo {
            requests++
            if (requests < 3) throw IOException("connection reset")
            json(
                """{"Items":[{"Id":"m1","Name":"电影A","Type":"Movie",""" +
                    """"MediaSources":[{"Id":"source","MediaStreams":[{"Type":"Video","Height":1080}]}]}]}""",
            )
        }

        val result = repo.compareSources(listOf(server), server.id, "电影A").single()

        assertEquals(3, requests)
        assertTrue(result.reachable)
        assertEquals("1080P", result.source?.quality)
    }

    @Test
    fun compareSources_retries_server_errors_up_to_success() = runTest {
        var requests = 0
        val repo = testRepo {
            requests++
            if (requests < 3) {
                respond(content = "", status = HttpStatusCode.ServiceUnavailable)
            } else {
                json(
                    """{"Items":[{"Id":"m1","Name":"电影A","Type":"Movie",""" +
                        """"MediaSources":[{"Id":"source","MediaStreams":[{"Type":"Video","Height":720}]}]}]}""",
                )
            }
        }

        val result = repo.compareSources(listOf(server), server.id, "电影A").single()

        assertEquals(3, requests)
        assertTrue(result.reachable)
        assertEquals("720P", result.source?.quality)
    }

    @Test
    fun compareSources_does_not_retry_forbidden() = runTest {
        var requests = 0
        val repo = testRepo {
            requests++
            respond(content = "", status = HttpStatusCode.Forbidden)
        }

        val result = repo.compareSources(listOf(server), server.id, "电影A").single()

        assertEquals(1, requests)
        assertFalse(result.reachable)
        assertEquals(null, result.source)
    }

    @Test
    fun compareSources_keeps_found_resource_when_stream_metadata_is_unavailable() = runTest {
        val repo = testRepo { request ->
            if (request.url.encodedPath.endsWith("/Items/m1")) {
                json("""{"Id":"m1","Name":"电影A","Type":"Movie"}""")
            } else {
                json("""{"Items":[{"Id":"m1","Name":"电影A","Type":"Movie"}]}""")
            }
        }

        val sources = repo.compareSources(listOf(server), server.id, "电影A")

        assertEquals("已有资源", sources.single().source?.summary)
    }

    @Test
    fun itemDetail_parses_full_detail() = runTest {
        val repo = testRepo {
            json(
                """{"Id":"m1","Name":"电影A","Type":"Movie","ProductionYear":2026,"Genres":["犯罪"],""" +
                    """"RunTimeTicks":41657170000,"Overview":"一段简介","People":[{"Id":"p1","Name":"演员A",""" +
                    """"Role":"角色","Type":"Actor","PrimaryImageTag":"pt"}],"ImageTags":{"Primary":"t"},""" +
                    """"BackdropImageTags":["bt"]}""",
            )
        }

        val res = repo.itemDetail(server, "m1")

        assertTrue(res.isSuccess, res.toString())
        val d = res.getOrThrow()
        assertEquals("电影A", d.title)
        assertEquals(2026, d.year)
        assertEquals(69, d.runtimeMinutes)
        assertEquals("犯罪", d.genres.first())
        assertEquals(1, d.people.size)
        assertEquals("演员A", d.people.first().name)
    }

    private fun detail(id: String, type: String, resume: Long? = null) = MediaDetail(
        id = id,
        title = "T",
        type = type,
        seriesId = null,
        overview = null,
        year = null,
        genres = emptyList(),
        runtimeMinutes = null,
        officialRating = null,
        communityRating = null,
        posterItemId = id,
        posterTag = null,
        backdropItemId = id,
        backdropTag = null,
        resumePositionTicks = resume,
        people = emptyList(),
    )

    @Test
    fun resolvePlayTarget_movie_plays_itself_from_resume_position() = runTest {
        val repo = testRepo { json("{}") }

        val res = repo.resolvePlayTarget(server, detail("m1", "Movie", resume = 12_345L))

        assertTrue(res.isSuccess, res.toString())
        assertEquals("m1", res.getOrThrow().itemId)
        assertEquals(12_345L, res.getOrThrow().startPositionTicks)
    }

    @Test
    fun resolvePlayTarget_series_uses_next_up_episode() = runTest {
        val repo = testRepo { req ->
            if (req.url.encodedPath.contains("NextUp")) {
                json("""{"Items":[{"Id":"e9","Name":"第9集","Type":"Episode","UserData":{"PlaybackPositionTicks":999}}]}""")
            } else {
                json("""{"Items":[]}""")
            }
        }

        val res = repo.resolvePlayTarget(server, detail("s1", "Series"))

        assertTrue(res.isSuccess, res.toString())
        assertEquals("e9", res.getOrThrow().itemId)
        assertEquals(999L, res.getOrThrow().startPositionTicks)
    }

    @Test
    fun resolvePlayTarget_series_falls_back_to_first_episode() = runTest {
        val repo = testRepo { req ->
            if (req.url.encodedPath.contains("NextUp")) {
                json("""{"Items":[]}""")
            } else {
                json("""{"Items":[{"Id":"e1","Name":"第1集","Type":"Episode"}]}""")
            }
        }

        val res = repo.resolvePlayTarget(server, detail("s1", "Series"))

        assertTrue(res.isSuccess, res.toString())
        assertEquals("e1", res.getOrThrow().itemId)
        assertEquals(0L, res.getOrThrow().startPositionTicks)
    }

    @Test
    fun episode_detail_falls_back_to_series_backdrop_and_cast() = runTest {
        val repo = testRepo { req ->
            if (req.url.encodedPath.endsWith("/ep1")) {
                json(
                    """{"Id":"ep1","Name":"第1集","Type":"Episode","SeriesId":"s1","SeriesName":"某剧",""" +
                        """"SeriesPrimaryImageTag":"sp","BackdropImageTags":[],""" +
                        """"ParentBackdropItemId":"s1","ParentBackdropImageTags":["pb"],"People":[]}""",
                )
            } else {
                // the series lookup that supplies the cast
                json("""{"Id":"s1","Name":"某剧","Type":"Series","People":[{"Id":"p1","Name":"演员A","Role":"角色"}]}""")
            }
        }

        val res = repo.itemDetail(server, "ep1")

        assertTrue(res.isSuccess, res.toString())
        val d = res.getOrThrow()
        // backdrop falls back to the series' backdrop
        assertEquals("s1", d.backdropItemId)
        assertEquals("pb", d.backdropTag)
        // poster falls back to the series poster
        assertEquals("s1", d.posterItemId)
        assertEquals("sp", d.posterTag)
        // cast is borrowed from the series
        assertEquals(1, d.people.size)
        assertEquals("演员A", d.people.first().name)
    }

    @Test
    fun seasons_and_episodes_parse() = runTest {
        val repo = testRepo { req ->
            if (req.url.encodedPath.contains("/Seasons")) {
                json("""{"Items":[{"Id":"se1","Name":"第 1 季","Type":"Season","IndexNumber":1,"ImageTags":{"Primary":"t"}}]}""")
            } else {
                json(
                    """{"Items":[{"Id":"e1","Name":"能听亡魂的女子","Type":"Episode","IndexNumber":1,""" +
                        """"SeasonId":"se1","RunTimeTicks":28063680000,"ImageTags":{"Primary":"ep"},""" +
                        """"UserData":{"PlayedPercentage":11.6,"PlaybackPositionTicks":123}}]}""",
                )
            }
        }

        val seasons = repo.seasons(server, "s1")
        assertTrue(seasons.isSuccess, seasons.toString())
        assertEquals("第 1 季", seasons.getOrThrow().first().name)

        val episodes = repo.episodes(server, "s1", "se1")
        assertTrue(episodes.isSuccess, episodes.toString())
        val ep = episodes.getOrThrow().first()
        assertEquals(1, ep.indexNumber)
        assertEquals("能听亡魂的女子", ep.name)
        assertEquals(46, ep.runtimeMinutes)
        assertEquals(123L, ep.resumePositionTicks)
    }

    @Test
    fun playback_events_post_to_emby_session_endpoints() = runTest {
        val paths = mutableListOf<String>()
        val repo = testRepo { request ->
            paths += request.url.encodedPath
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("tok", request.headers["X-Emby-Token"])
            json("{}")
        }

        assertTrue(repo.reportPlaybackStarted(server, "e1", "session-1", 12_000L, false).isSuccess)
        assertTrue(repo.reportPlaybackProgress(server, "e1", "session-1", 34_000L, true).isSuccess)
        assertTrue(repo.reportPlaybackStopped(server, "e1", "session-1", 56_000L, true).isSuccess)

        assertEquals(
            listOf(
                "/Sessions/Playing",
                "/Sessions/Playing/Progress",
                "/Sessions/Playing/Stopped",
            ),
            paths,
        )
    }

    /**
     * The snapshot was one `Limit=10000` request. Every library larger than that synced
     * silently truncated and still reported success — across a week of real logs the reported
     * item count was 10000 or 1000 and never once a genuine total.
     */
    @Test
    fun user_library_snapshot_pages_until_the_server_total_is_reached() = runTest {
        val requested = mutableListOf<String>()
        val total = 4_500
        val repo = testRepo { request ->
            requested += request.url.parameters["StartIndex"].orEmpty()
            val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
            val limit = request.url.parameters["Limit"]?.toInt() ?: 0
            val page = (start until minOf(start + limit, total)).map { index ->
                """{"Id":"i$index","Name":"标题$index","UserData":{"Played":true}}"""
            }
            json("""{"Items":[${page.joinToString(",")}],"TotalRecordCount":$total}""")
        }

        val res = repo.userLibrarySnapshot(server)

        assertTrue(res.isSuccess, res.toString())
        assertEquals(total, res.getOrThrow().size)
        assertEquals("i0", res.getOrThrow().first().id)
        assertEquals("i4499", res.getOrThrow().last().id)
        assertEquals(listOf("0", "2000", "4000"), requested)
    }

    @Test
    fun a_server_that_ignores_start_index_does_not_loop_forever() = runTest {
        // An empty page is the only signal that a server which reports a large total but
        // will not paginate has actually run out of rows.
        var calls = 0
        val repo = testRepo {
            calls++
            json("""{"Items":[],"TotalRecordCount":999999}""")
        }

        val res = repo.userLibrarySnapshot(server)

        assertTrue(res.isSuccess, res.toString())
        assertEquals(0, res.getOrThrow().size)
        assertEquals(1, calls)
    }

    /** Emby uses 403 as well as 401 when a token/account is no longer valid. */
    @Test
    fun emby_forbidden_is_an_authentication_failure() = runTest {
        val repo = testRepo { respond(content = "", status = HttpStatusCode.Forbidden) }

        val res = repo.userLibrarySnapshot(server)

        assertTrue(res.isFailure)
        assertEquals(EmbyError.Unauthorized, (res.exceptionOrNull() as EmbyErrorException).error)
    }

    @Test
    fun cloudflare_forbidden_is_an_access_block_not_an_authentication_failure() = runTest {
        val repo = testRepo {
            respond(
                content = "<!doctype html><title>Attention Required | Cloudflare</title>" +
                    "<p>Sorry, you have been blocked</p>",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=UTF-8"),
            )
        }

        val res = repo.userLibrarySnapshot(server)

        assertTrue(res.isFailure)
        val error = (res.exceptionOrNull() as EmbyErrorException).error
        assertEquals(EmbyError.AccessDenied(provider = "Cloudflare"), error)
        assertEquals(
            "访问被 Cloudflare 拦截，请更换网络或联系服务器管理员",
            error.toUserMessage(),
        )
    }

    @Test
    fun stopping_a_transcode_names_the_device_and_play_session() = runTest {
        var path: String? = null
        var query: String? = null
        val repo = testRepo { request ->
            path = request.url.encodedPath
            query = request.url.encodedQuery
            assertEquals(HttpMethod.Delete, request.method)
            json("{}")
        }

        assertTrue(repo.stopTranscoding(server, "yfuse-abc").isSuccess)

        assertEquals("/Videos/ActiveEncodings", path)
        assertTrue(query!!.contains("PlaySessionId=yfuse-abc"), query!!)
        assertTrue(query!!.contains("DeviceId="), query!!)
    }

    @Test
    fun stopping_an_already_gone_transcode_is_idempotent_success() = runTest {
        listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone).forEach { status ->
            val repo = testRepo { respond(content = "", status = status) }

            assertTrue(repo.stopTranscoding(server, "yfuse-gone").isSuccess, status.toString())
        }
    }
}
