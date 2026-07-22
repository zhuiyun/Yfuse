package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.createEmbyClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbyRepositoryTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun repo(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): Pair<EmbyRepository, SessionManager> {
        val session = SessionManager(MapSettings())
        val client = createEmbyClient(MockEngine(handler)) { session.token() }
        return EmbyRepository(client, session) to session
    }

    @Test
    fun login_success_saves_session() = runTest {
        val (r, s) = repo {
            respond(
                content = ByteReadChannel(
                    """{"AccessToken":"tok","User":{"Id":"u1","Name":"zhuiyun"}}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val res = r.login("http://host:8096", "zhuiyun", "123456")

        assertTrue(res.isSuccess, res.toString())
        assertEquals("tok", res.getOrThrow().accessToken)
        assertEquals("u1", res.getOrThrow().id)
        assertTrue(s.hasSession())
    }

    @Test
    fun login_401_returns_unauthorized() = runTest {
        val (r, _) = repo { respond(content = "", status = HttpStatusCode.Unauthorized) }

        val res = r.login("http://host:8096", "x", "y")

        assertTrue(res.isFailure)
        val err = (res.exceptionOrNull() as EmbyErrorException).error
        assertEquals(EmbyError.Unauthorized, err)
    }

    @Test
    fun libraries_parses_items() = runTest {
        val (r, s) = repo {
            respond(
                content = ByteReadChannel(
                    """{"Items":[{"Id":"1","Name":"电影","CollectionType":"movies"},""" +
                        """{"Id":"2","Name":"综艺","CollectionType":"tvshows"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        s.save("http://host:8096", "tok", "u1")

        val res = r.libraries()

        assertTrue(res.isSuccess, res.toString())
        assertEquals(2, res.getOrThrow().size)
        assertEquals("电影", res.getOrThrow().first().name)
        assertEquals("movies", res.getOrThrow().first().collectionType)
    }

    @Test
    fun checkServer_returns_name() = runTest {
        val (r, _) = repo {
            respond(
                content = ByteReadChannel("""{"ServerName":"zhuiyun","Version":"4.9.1.90"}"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val res = r.checkServer("http://host:8096")

        assertTrue(res.isSuccess, res.toString())
        assertEquals("zhuiyun", res.getOrThrow())
    }

    @Test
    fun checkServer_network_failure_maps_to_network_error() = runTest {
        val (r, _) = repo { throw RuntimeException("boom") }

        val res = r.checkServer("http://host:8096")

        assertFalse(res.isSuccess)
        assertEquals(EmbyError.Network, (res.exceptionOrNull() as EmbyErrorException).error)
    }
}
