package com.yfuse.core.data

import com.yfuse.core.data.dto.PlaybackInfoRequestDto
import com.yfuse.core.data.dto.PlaybackReportDto
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.createEmbyClient
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbyPlaybackServiceTest {
    private val server = SavedServer("one", "http://host:8096/", "Media", "u1", "viewer", "token")
    private val requestJson = Json { ignoreUnknownKeys = true }

    @Test
    fun progress_reports_post_the_session_payload_with_a_non_negative_position() =
        runTest {
            val paths = mutableListOf<String>()
            var body = ""
            val client =
                client { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("token", request.headers["X-Emby-Token"])
                    paths += request.url.encodedPath
                    body = request.body.toByteArray().decodeToString()
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
            try {
                val service = service(client)

                assertTrue(service.reportStarted(server, "item-1", "ps-1", 0L, false, "DirectPlay").isSuccess)
                assertTrue(service.reportProgress(server, "item-1", "ps-1", -5L, true, "DirectStream").isSuccess)
                val report = requestJson.decodeFromString(PlaybackReportDto.serializer(), body)
                assertEquals("item-1", report.ItemId)
                assertEquals("ps-1", report.PlaySessionId)
                assertEquals(0L, report.PositionTicks)
                assertTrue(report.IsPaused)
                assertEquals("DirectStream", report.PlayMethod)
                assertTrue(service.reportStopped(server, "item-1", "ps-1", 42L, true, "Transcode").isSuccess)
                assertEquals(
                    listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"),
                    paths,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun playback_info_negotiates_with_the_device_profile_and_returns_the_media_sources() =
        runTest {
            var seen: HttpRequestData? = null
            var body = ""
            val client =
                client { request ->
                    seen = request
                    body = request.body.toByteArray().decodeToString()
                    json("""{"MediaSources":[{"Id":"src","SupportsDirectPlay":true}],"PlaySessionId":"ps-1"}""")
                }
            try {
                val response =
                    service(client)
                        .playbackInfo(
                            server = server,
                            itemId = "item-1",
                            mediaSourceId = "src",
                            startPositionTicks = -100L,
                            playSessionId = "ps-1",
                            sourceRequiresDolbyDecoder = false,
                        ).getOrThrow()

                val request = requireNotNull(seen)
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/Items/item-1/PlaybackInfo", request.url.encodedPath)
                assertEquals("token", request.headers["X-Emby-Token"])
                val negotiation = requestJson.decodeFromString(PlaybackInfoRequestDto.serializer(), body)
                assertEquals("item-1", negotiation.Id)
                assertEquals("u1", negotiation.UserId)
                assertEquals("src", negotiation.MediaSourceId)
                assertEquals(0L, negotiation.StartTimeTicks)
                assertTrue(negotiation.EnableDirectPlay)
                assertTrue(negotiation.EnableDirectStream)
                assertTrue(negotiation.EnableTranscoding)
                assertEquals("ps-1", response.PlaySessionId)
                assertEquals(listOf("src"), response.MediaSources.map { it.Id })
            } finally {
                client.close()
            }
        }

    @Test
    fun stopping_a_transcoder_that_already_ended_is_not_a_failure() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    respond(content = "", status = HttpStatusCode.NotFound)
                }
            try {
                assertTrue(service(client).stopTranscoding(server, "ps-1").isSuccess)

                val request = requireNotNull(seen)
                assertEquals(HttpMethod.Delete, request.method)
                assertEquals("/Videos/ActiveEncodings", request.url.encodedPath)
                assertEquals("ps-1", request.url.parameters["PlaySessionId"])
                assertFalse(request.url.parameters["DeviceId"].isNullOrBlank())
            } finally {
                client.close()
            }
        }

    @Test
    fun an_unnamed_play_session_never_asks_the_server_to_end_encodings() =
        runTest {
            var requests = 0
            val client =
                client {
                    requests++
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
            try {
                // DeviceId alone would end every encoding this device has running.
                assertTrue(service(client).stopTranscoding(server, " ").isSuccess)
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun a_transcoder_server_error_still_surfaces_as_a_failure() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.InternalServerError) }
            try {
                val result = service(client).stopTranscoding(server, "ps-1")

                assertTrue(result.isFailure)
                assertEquals(EmbyError.Server(500), assertIs<EmbyErrorException>(result.exceptionOrNull()).error)
            } finally {
                client.close()
            }
        }

    private fun service(client: HttpClient) =
        EmbyPlaybackService(
            client = client,
            capabilitiesProvider = PlaybackDeviceCapabilitiesProvider { PlaybackDeviceCapabilities.conservative() },
            audioPassthroughEnabled = { false },
        )

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
