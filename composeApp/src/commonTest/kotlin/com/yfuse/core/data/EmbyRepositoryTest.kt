package com.yfuse.core.data

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.feature.authRoutes
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val repo = testRepo { throw RuntimeException("boom") }

        val res = repo.libraries(server)

        assertTrue(res.isFailure)
        assertEquals(EmbyError.Network, (res.exceptionOrNull() as EmbyErrorException).error)
    }

    @Test
    fun homeContent_aggregates_resume_latest_and_featured() = runTest {
        val repo = testRepo { homeRoutes(it) }

        val res = repo.homeContent(server)

        assertTrue(res.isSuccess, res.toString())
        val content = res.getOrThrow()
        // resume episode maps to its series title + poster
        assertEquals(1, content.resume.size)
        assertEquals("某剧", content.resume.first().title)
        assertEquals("s1", content.resume.first().posterItemId)
        // latest row present
        assertTrue(content.rows.isNotEmpty())
        assertEquals("某电影", content.rows.first().items.first().title)
        // featured only includes items with a backdrop (the movie, not the episode)
        assertEquals(1, content.featured.size)
        assertEquals("某电影", content.featured.first().title)
    }

    @Test
    fun libraryItems_parses_movies() = runTest {
        val repo = testRepo {
            json("""{"Items":[{"Id":"m1","Name":"电影A","Type":"Movie","ProductionYear":2026,"ImageTags":{"Primary":"t"}}]}""")
        }

        val res = repo.libraryItems(server, "lib1")

        assertTrue(res.isSuccess, res.toString())
        assertEquals(1, res.getOrThrow().size)
        assertEquals("电影A", res.getOrThrow().first().title)
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
}
