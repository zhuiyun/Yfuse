package com.yfuse.core.data

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.createEmbyClient
import com.yfuse.feature.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataEditorServiceTest {
    private val server = SavedServer("s", "http://server", "媒体", "u", "user", "test-token")
    private val original =
        """{"Id":"42","Name":"Before","Overview":"Summary","ProviderIds":{"Tmdb":"123","Imdb":"tt0001"},""" +
            """"Genres":["Drama"],"Custom":{"preserve":true}}"""

    @Test fun edit_preserves_unknown_fields_and_other_provider_ids() =
        runTest {
            var written = false
            val client =
                createEmbyClient(
                    "test",
                    engine =
                        MockEngine { request ->
                            assertEquals("test-token", request.headers["X-Emby-Token"])
                            assertFalse(request.url.toString().contains("test-token"))
                            if (request.method == HttpMethod.Post) {
                                assertEquals("/Items/42", request.url.encodedPath)
                                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                                assertEquals("After", body["Name"]?.jsonPrimitive?.content)
                                assertEquals(
                                    "tt0001",
                                    body["ProviderIds"]
                                        ?.jsonObject
                                        ?.get("Imdb")
                                        ?.jsonPrimitive
                                        ?.content,
                                )
                                assertEquals(
                                    "456",
                                    body["ProviderIds"]
                                        ?.jsonObject
                                        ?.get("Tmdb")
                                        ?.jsonPrimitive
                                        ?.content,
                                )
                                assertEquals(Json.parseToJsonElement(original).jsonObject["Custom"], body["Custom"])
                                assertEquals(Json.parseToJsonElement(original).jsonObject["Genres"], body["Genres"])
                                written = true
                                respond("", HttpStatusCode.NoContent)
                            } else {
                                json(original)
                            }
                        },
                    timeouts = null,
                )
            try {
                val service = MetadataEditorService(client)
                val loaded = service.load(server, "42")
                service.save(server, loaded, loaded.draft.copy(title = " After ", tmdbId = "456"))
                assertTrue(written)
            } finally {
                client.close()
            }
        }

    @Test fun concurrent_change_prevents_write_and_forbidden_is_not_success() =
        runTest {
            var gets = 0
            val client =
                createEmbyClient(
                    "test",
                    engine =
                        MockEngine { request ->
                            if (request.method == HttpMethod.Get) {
                                gets++
                                json(if (gets == 1) original else original.replace("Before", "Changed elsewhere"))
                            } else {
                                error("Must not write stale metadata")
                            }
                        },
                    timeouts = null,
                )
            try {
                val service = MetadataEditorService(client)
                val loaded = service.load(server, "42")
                assertFailsWith<IllegalStateException> {
                    service.save(
                        server,
                        loaded,
                        loaded.draft.copy(title = "After"),
                    )
                }
            } finally {
                client.close()
            }
            val forbidden =
                createEmbyClient(
                    "test",
                    engine =
                        MockEngine { request ->
                            if (request.method ==
                                HttpMethod.Get
                            ) {
                                json(original)
                            } else {
                                respond("Forbidden", HttpStatusCode.Forbidden)
                            }
                        },
                    timeouts = null,
                )
            try {
                val service = MetadataEditorService(forbidden)
                val loaded = service.load(server, "42")
                assertFailsWith<ClientRequestException> {
                    service.save(
                        server,
                        loaded,
                        loaded.draft.copy(title = "After"),
                    )
                }
            } finally {
                forbidden.close()
            }
        }

    @Test fun plex_artwork_selection_uses_rating_key_not_preview_key() =
        runTest {
            var selected = false
            val client =
                createEmbyClient(
                    "test",
                    engine =
                        MockEngine { request ->
                            assertEquals("test-token", request.headers["X-Plex-Token"])
                            if (request.method == HttpMethod.Get) {
                                json(
                                    """{"MediaContainer":{"Metadata":[{"ratingKey":"metadata://poster/one","key":"/library/metadata/42/thumb/1","thumb":"/library/metadata/42/thumb/1","provider":"local"}]}}""",
                                )
                            } else {
                                assertEquals("/library/metadata/42/poster", request.url.encodedPath)
                                assertEquals("metadata://poster/one", request.url.parameters["url"])
                                selected = true
                                respond("", HttpStatusCode.NoContent)
                            }
                        },
                    timeouts = null,
                )
            try {
                val service = MetadataEditorService(client)
                val plex = server.copy(kind = MediaServerKind.Plex)
                service.selectArtwork(plex, "42", service.artwork(plex, "42", "Primary").single())
                assertTrue(selected)
            } finally {
                client.close()
            }
        }
}
