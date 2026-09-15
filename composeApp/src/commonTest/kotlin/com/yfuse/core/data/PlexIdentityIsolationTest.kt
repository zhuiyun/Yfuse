package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexIdentityIsolationTest {
    private val resource =
        PlexCloudResource(
            "machine-1",
            "家庭服务器",
            true,
            "kid-server-token",
            listOf(PlexCloudConnection("https://plex.example", local = false, relay = false)),
        )

    @Test
    fun verifiedHomeSubjectsKeepChildLinksAwayFromReplacedAdultCredentials() =
        runTest {
            val repo =
                testRepo { request ->
                    when (request.url.encodedPath) {
                        "/api/v2/user" ->
                            when (request.headers["X-Plex-Token"]) {
                                "kid-cloud-token" -> json("""{"id":2,"uuid":"kid","title":"孩子"}""")
                                "owner-cloud-token" -> json("""{"id":1,"uuid":"owner","title":"家长"}""")
                                else -> error("Unexpected account token")
                            }
                        "/api/v2/home/users/owner/switch" -> {
                            assertEquals("owner-cloud-token", request.headers["X-Plex-Token"])
                            assertEquals("5839", request.url.parameters["pin"])
                            json("""{"id":1,"uuid":"owner","title":"家长","authToken":"owner-cloud-token"}""")
                        }
                        "/api/v2/resources" -> {
                            assertEquals("owner-cloud-token", request.headers["X-Plex-Token"])
                            json(
                                """[{"name":"家庭服务器","product":"Plex Media Server","clientIdentifier":"machine-1","accessToken":"adult-server-token","connections":[{"uri":"https://plex.example"}]}]""",
                            )
                        }
                        "/identity" -> json("""{"MediaContainer":{"machineIdentifier":"machine-1"}}""")
                        "/" -> json("""{"MediaContainer":{"friendlyName":"家庭服务器","myPlexUsername":"same-owner"}}""")
                        else -> error("Unexpected mock request ${request.url.encodedPath}")
                    }
                }
            val childServer =
                repo
                    .authenticatePlexCloudResource(
                        "kid-cloud-token",
                        resource,
                        "owner-cloud-token",
                    ).getOrThrow()
                    .toSavedServer()
            assertEquals("plex-user:2", childServer.userId)
            assertEquals("孩子", childServer.userName)

            val settings = MapSettings()
            val personal = PersonalLibraryRepository(settings)
            val registry = ServerRegistry(settings, TestSecureStore(), personal = personal)
            registry.addOrUpdate(childServer)
            personal.setGuardianPin("583921".toCharArray()).getOrThrow()
            personal.saveProfile(name = "孩子", child = true, serverIds = setOf(childServer.id)).getOrThrow()
            val childProfile =
                personal.state.value.profiles
                    .last()
                    .id

            val adultServer = repo.switchPlexServerHomeUser(childServer, "owner", "5839").getOrThrow().toSavedServer()
            assertEquals("plex-user:1", adultServer.userId)
            assertEquals("adult-server-token", adultServer.accessToken)
            assertNotEquals(childServer.id, adultServer.id)
            assertTrue(registry.replace(childServer.id, adultServer))
            personal.switchProfile(childProfile).getOrThrow()
            assertTrue(personal.canAccessServer(childServer.id))
            assertFalse(personal.canAccessServer(adultServer.id))
            assertNull(registry.serverById(childServer.id))
            assertNull(registry.serverById(adultServer.id))
            assertNull(registry.defaultServer)
            assertTrue(
                registry.data.value.servers
                    .isEmpty(),
            )
        }

    @Test
    fun homeSwitchRejectsMissingOrDifferentResponseSubjectsAndTokens() =
        runTest {
            val replies =
                listOf(
                    """{"authToken":"switched-token"}""" to """{"id":2,"uuid":"kid"}""",
                    """{"id":1,"uuid":"owner","authToken":"switched-token"}""" to """{"id":1,"uuid":"owner"}""",
                    """{"id":2,"uuid":"kid","authToken":"switched-token"}""" to """{"id":1,"uuid":"owner"}""",
                )
            replies.forEach { (switchReply, currentReply) ->
                val repo =
                    testRepo { request ->
                        when (request.url.encodedPath) {
                            "/api/v2/home/users/kid/switch" -> json(switchReply)
                            "/api/v2/user" -> json(currentReply)
                            else -> error("Rejected identity must not reach a media server")
                        }
                    }
                assertTrue(repo.switchPlexHomeUser("owner-token", "kid", "").isFailure)
            }
        }

    @Test
    fun initialCloudConnectionRequiresAnActiveSubjectInsteadOfTheServerOwnerLabel() =
        runTest {
            var serverRequests = 0
            val repo =
                testRepo { request ->
                    if (request.url.encodedPath == "/api/v2/user") {
                        assertEquals("active-cloud-token", request.headers["X-Plex-Token"])
                        json("""{"username":"server-owner-without-an-id"}""")
                    } else {
                        serverRequests++
                        json("""{"MediaContainer":{"machineIdentifier":"machine-1","myPlexUsername":"owner"}}""")
                    }
                }
            assertTrue(repo.authenticatePlexCloudResource("active-cloud-token", resource, "owner-token").isFailure)
            assertEquals(0, serverRequests)
        }

    @Test
    fun manualLanTokensRemainOfflineAndNeverShareAnOwnerDerivedIdentity() =
        runTest {
            val repo =
                testRepo { request ->
                    assertEquals("plex.example", request.url.host)
                    assertTrue(request.url.encodedPath in setOf("/", "/identity"))
                    json("""{"MediaContainer":{"machineIdentifier":"machine-1","myPlexUsername":"same-owner"}}""")
                }

            suspend fun authenticate(token: String) =
                repo
                    .authenticate(
                        "https://plex.example",
                        "ignored-name",
                        token,
                        MediaServerKind.Plex,
                    ).getOrThrow()
                    .toSavedServer()
            val kid = authenticate("kid-manual-token")
            val adult = authenticate("adult-manual-token")
            assertEquals(kid.id, authenticate("kid-manual-token").id)
            assertNotEquals(kid.id, adult.id)
            assertTrue(kid.userId.startsWith("plex-token-sha256:"))
            assertFalse(kid.userId.contains("kid-manual-token"))
            assertFalse(kid.userId.contains("same-owner"))
        }
}
