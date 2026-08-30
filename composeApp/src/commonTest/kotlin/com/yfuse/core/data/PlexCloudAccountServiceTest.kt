package com.yfuse.core.data

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlexCloudAccountServiceTest {
    @Test
    fun pin_login_uses_cloud_headers_and_never_places_token_in_url() =
        runTest {
            val seen = mutableListOf<String>()
            val repo =
                testRepo { request ->
                    seen += request.url.toString()
                    assertEquals(null, request.headers["X-Emby-Authorization"])
                    assertTrue(request.headers["X-Plex-Client-Identifier"].orEmpty().isNotBlank())
                    when (request.url.encodedPath) {
                        "/api/v2/pins" -> {
                            assertEquals(HttpMethod.Post, request.method)
                            assertEquals("true", request.url.parameters["strong"])
                            json("""{"id":42,"code":"ABCD","expiresIn":300}""")
                        }
                        "/api/v2/pins/42" -> {
                            assertEquals("ABCD", request.url.parameters["code"])
                            json("""{"id":42,"code":"ABCD","authToken":"cloud-secret","expiresIn":250}""")
                        }
                        else -> error("unexpected ${request.url}")
                    }
                }

            val pin = repo.startPlexCloudSignIn(1_000L).getOrThrow()
            val poll = repo.pollPlexCloudSignIn(pin, 2_000L).getOrThrow()

            assertTrue(pin.authUrl.startsWith("https://app.plex.tv/auth#?"))
            assertEquals("cloud-secret", poll.accessToken)
            assertTrue(seen.none { "cloud-secret" in it })
        }

    @Test
    fun home_switch_and_resource_login_preserve_cloud_token_and_fallback_routes() =
        runTest {
            val repo =
                testRepo { request ->
                    when (request.url.encodedPath) {
                        "/api/v2/home/users" ->
                            json(
                                """[{"uuid":"owner","title":"Owner","admin":true},""" +
                                    """{"uuid":"kid","title":"Kid","protected":true}]""",
                            )
                        "/api/v2/home/users/kid/switch" -> {
                            assertEquals("2468", request.url.parameters["pin"])
                            assertEquals("owner-token", request.headers["X-Plex-Token"])
                            json("""{"uuid":"kid","title":"Kid","authToken":"kid-token"}""")
                        }
                        "/api/v2/resources" -> {
                            assertEquals("kid-token", request.headers["X-Plex-Token"])
                            json(
                                """[{"name":"客厅","product":"Plex Media Server","clientIdentifier":"m1","owned":true,"accessToken":"server-token","provides":"server","connections":[{"uri":"https://plex.example.com:32400","local":false,"relay":false},{"uri":"https://relay.plex.direct:443","local":false,"relay":true}]}]""",
                            )
                        }
                        "/identity" ->
                            json("""{"MediaContainer":{"machineIdentifier":"m1"}}""")
                        "/" ->
                            json("""{"MediaContainer":{"friendlyName":"客厅","myPlexUsername":"Kid"}}""")
                        else -> error("unexpected ${request.url}")
                    }
                }

            val users = repo.plexHomeUsers("owner-token").getOrThrow()
            val token = repo.switchPlexHomeUser("owner-token", "kid", "2468").getOrThrow()
            val resource = repo.plexCloudResources(token).getOrThrow().single()
            val authenticated =
                repo
                    .authenticatePlexCloudResource(
                        accountToken = token,
                        resource = resource,
                        ownerAccountToken = "owner-token",
                    ).getOrThrow()

            assertTrue(users.single { it.id == "kid" }.pinProtected)
            assertEquals("kid-token", authenticated.cloudAccessToken)
            assertEquals("owner-token", authenticated.cloudOwnerAccessToken)
            assertEquals("server-token", authenticated.accessToken)
            assertEquals(2, authenticated.routes.size)
            assertEquals("https://plex.example.com:32400", authenticated.baseUrl)
        }

    @Test
    fun watchlist_uses_cloud_identity_and_cloud_token() =
        runTest {
            val server =
                SavedServer(
                    id = "plex",
                    baseUrl = "https://plex.example.com",
                    serverName = "Plex",
                    userId = "u",
                    userName = "U",
                    accessToken = "server-token",
                    cloudAccessToken = "cloud-token",
                    kind = MediaServerKind.Plex,
                )
            var wroteCloudKey = false
            val repo =
                testRepo { request ->
                    when (request.url.encodedPath) {
                        "/library/metadata/100" -> {
                            assertEquals("server-token", request.headers["X-Plex-Token"])
                            json(
                                """{"MediaContainer":{"Metadata":[{"ratingKey":"100","guid":"plex://movie/cloud-abc"}]}}""",
                            )
                        }
                        "/actions/addToWatchlist" -> {
                            wroteCloudKey = true
                            assertEquals(HttpMethod.Put, request.method)
                            assertEquals("cloud-token", request.headers["X-Plex-Token"])
                            assertEquals("cloud-abc", request.url.parameters["ratingKey"])
                            assertFalse(request.url.toString().contains("cloud-token"))
                            json("{}")
                        }
                        else -> error("unexpected ${request.url}")
                    }
                }

            assertTrue(repo.addToWatchLater(server, "100").isSuccess)
            assertTrue(wroteCloudKey)
        }
}
