package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.feature.authRoutes
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
}
