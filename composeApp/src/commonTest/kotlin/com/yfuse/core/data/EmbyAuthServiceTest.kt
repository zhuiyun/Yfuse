package com.yfuse.core.data

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.createEmbyClient
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbyAuthServiceTest {
    @Test
    fun authenticate_posts_the_credentials_and_reads_the_public_server_identity() =
        runTest {
            var requestBody = ""
            val client =
                client { request ->
                    when {
                        request.url.encodedPath.endsWith("/Users/AuthenticateByName") -> {
                            assertEquals(HttpMethod.Post, request.method)
                            requestBody = request.body.toByteArray().decodeToString()
                            json("""{"AccessToken":"tok","User":{"Id":"u1","Name":"zhuiyun"}}""")
                        }
                        request.url.encodedPath.endsWith("/System/Info/Public") ->
                            json("""{"ServerName":"Home","ProductName":"Jellyfin Server"}""")
                        else -> error("unexpected request ${request.url}")
                    }
                }
            try {
                val authed =
                    EmbyAuthService(client)
                        .authenticate("http://host:8096/", "zhuiyun", "secret")
                        .getOrThrow()

                assertEquals("http://host:8096", authed.baseUrl)
                assertEquals("Home", authed.serverName)
                assertEquals("u1", authed.userId)
                assertEquals("zhuiyun", authed.userName)
                assertEquals("tok", authed.accessToken)
                assertEquals(MediaServerKind.Jellyfin, authed.kind)
                assertTrue("\"Username\":\"zhuiyun\"" in requestBody)
                assertTrue("\"Pw\":\"secret\"" in requestBody)
            } finally {
                client.close()
            }
        }

    @Test
    fun authenticate_still_succeeds_when_the_public_info_endpoint_is_broken() =
        runTest {
            val client =
                client { request ->
                    if (request.url.encodedPath.endsWith("/Users/AuthenticateByName")) {
                        json("""{"AccessToken":"tok","User":{"Id":"u1","Name":"zhuiyun"}}""")
                    } else {
                        respond(content = "boom", status = HttpStatusCode.InternalServerError)
                    }
                }
            try {
                val authed = EmbyAuthService(client).authenticate("http://host:8096", "zhuiyun", "pw").getOrThrow()

                assertEquals("http://host:8096", authed.serverName)
                assertEquals(MediaServerKind.Emby, authed.kind)
            } finally {
                client.close()
            }
        }

    @Test
    fun rejected_credentials_map_to_the_unauthorized_domain_error() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.Unauthorized) }
            try {
                val result = EmbyAuthService(client).authenticate("http://host:8096", "zhuiyun", "wrong")

                assertTrue(result.isFailure)
                assertEquals(EmbyError.Unauthorized, assertIs<EmbyErrorException>(result.exceptionOrNull()).error)
            } finally {
                client.close()
            }
        }

    @Test
    fun public_users_are_listed_from_the_normalized_base_url() =
        runTest {
            var requestedUrl = ""
            val client =
                client { request ->
                    requestedUrl = request.url.toString()
                    json("""[{"Id":"u1","Name":"zhuiyun","HasPassword":true},{"Id":"u2"}]""")
                }
            try {
                val users = EmbyAuthService(client).publicUsers("http://host:8096/").getOrThrow()

                assertEquals("http://host:8096/Users/Public", requestedUrl)
                assertEquals(listOf("u1", "u2"), users.map { it.Id })
                assertEquals(listOf(true, false), users.map { it.HasPassword })
                assertEquals("", users.last().Name)
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
