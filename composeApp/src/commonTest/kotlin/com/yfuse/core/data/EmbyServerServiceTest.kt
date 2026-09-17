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
import kotlin.test.assertTrue

class EmbyServerServiceTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun probe_reads_system_info_with_the_server_token_and_reports_a_latency() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json("""{"Id":"srv","ServerName":"Media"}""")
                }
            try {
                val latencyMs = EmbyServerService(client).probe(server).getOrThrow()

                val request = requireNotNull(seen)
                assertEquals("http://host:8096/System/Info", request.url.toString())
                assertEquals("token", request.headers["X-Emby-Token"])
                assertTrue(latencyMs >= 0L)
            } finally {
                client.close()
            }
        }

    @Test
    fun probe_address_normalizes_the_scheme_and_trailing_slash() =
        runTest {
            var requestedUrl = ""
            val client =
                client { request ->
                    requestedUrl = request.url.toString()
                    json("{}")
                }
            try {
                assertTrue(EmbyServerService(client).probeAddress("HTTP://host:8096/", "token").isSuccess)
                assertEquals("http://host:8096/System/Info", requestedUrl)
            } finally {
                client.close()
            }
        }

    @Test
    fun an_unavailable_server_maps_to_the_server_domain_error() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.ServiceUnavailable) }
            try {
                val result = EmbyServerService(client).probe(server)

                assertTrue(result.isFailure)
                assertEquals(EmbyError.Server(503), assertIs<EmbyErrorException>(result.exceptionOrNull()).error)
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
