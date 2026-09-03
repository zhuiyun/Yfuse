package com.yfuse.core.network

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpClientFactoryTest {
    @Test
    fun danmakuClientInstallsTimeoutProtection() {
        val client = createDanmakuClient(MockEngine { respond("{}", HttpStatusCode.OK) })

        try {
            assertNotNull(client.pluginOrNull(HttpTimeout))
        } finally {
            client.close()
        }
    }

    @Test
    fun embyClientDecodesJsonWhenSuccessfulResponseOmitsContentType() =
        runTest {
            val client =
                createEmbyClient(
                    engine =
                        MockEngine {
                            respond(
                                content = "{\"value\":42}",
                                status = HttpStatusCode.OK,
                            )
                        },
                    appVersion = "1.0.0",
                    timeouts = null,
                )

            try {
                val payload = client.get("https://media.example.com/Users/u1/Items").body<TestPayload>()
                assertEquals(42, payload.value)
            } finally {
                client.close()
            }
        }

    @Test
    fun embyClientKeepsNormalContentNegotiationWhenJsonContentTypeExists() =
        runTest {
            val client =
                createEmbyClient(
                    engine =
                        MockEngine {
                            respond(
                                content = "{\"value\":7}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        },
                    appVersion = "1.0.0",
                    timeouts = null,
                )

            try {
                val payload = client.get("https://media.example.com/Users/u1/Items").body<TestPayload>()
                assertEquals(7, payload.value)
            } finally {
                client.close()
            }
        }

    @Test
    fun embyIdentityHeaderUsesTheInjectedBuildVersion() =
        runTest {
            var authorization: String? = null
            var userAgent: String? = null
            var clientName: String? = null
            var clientVersion: String? = null
            var deviceId: String? = null
            var deviceName: String? = null
            val client =
                createEmbyClient(
                    engine =
                        MockEngine { request ->
                            authorization = request.headers["X-Emby-Authorization"]
                            userAgent = request.headers[HttpHeaders.UserAgent]
                            clientName = request.headers["X-Emby-Client"]
                            clientVersion = request.headers["X-Emby-Client-Version"]
                            deviceId = request.headers["X-Emby-Device-Id"]
                            deviceName = request.headers["X-Emby-Device-Name"]
                            respond("{}", HttpStatusCode.OK)
                        },
                    appVersion = "9.8.7",
                    timeouts = null,
                )

            try {
                client.get("https://example.invalid/System/Info/Public")
            } finally {
                client.close()
            }

            val identity = assertNotNull(authorization)
            assertTrue(identity.contains("Version=\"9.8.7\""))
            assertTrue(identity.contains("Client=\"Emby for Android Mobile\""))
            assertEquals("Emby for Android Mobile", userAgent)
            assertEquals("Emby for Android Mobile", clientName)
            assertEquals("9.8.7", clientVersion)
            assertTrue(assertNotNull(deviceId).isNotBlank())
            assertTrue(assertNotNull(deviceName).isNotBlank())
        }

    @Test
    fun successfulAuthenticatedRequestKeepsTheCurrentIdentity() =
        runTest {
            var authorizationValues: List<String>? = null
            var token: String? = null
            val client =
                createEmbyClient(
                    engine =
                        MockEngine { request ->
                            authorizationValues = request.headers.getAll("X-Emby-Authorization")
                            token = request.headers["X-Emby-Token"]
                            assertNull(request.headers[HttpHeaders.Authorization])
                            respond("{}", HttpStatusCode.OK)
                        },
                    appVersion = "9.8.7",
                    timeouts = null,
                )

            try {
                client.get("https://media.example.com/Users/u1/Views") {
                    header("X-Emby-Token", "token-123")
                }
            } finally {
                client.close()
            }

            assertEquals("token-123", token)
            val identity = assertNotNull(authorizationValues).single()
            assertTrue(identity.contains("Client=\"Emby for Android Mobile\""), identity)
        }

    @Test
    fun embyClientAllowsPublicAndLocalHttpAndHttps() =
        runTest {
            var engineCalls = 0
            val client =
                createEmbyClient(
                    engine =
                        MockEngine {
                            engineCalls += 1
                            respond("{}", HttpStatusCode.OK)
                        },
                    appVersion = "1.0.0",
                    timeouts = null,
                )

            try {
                client.get("http://media.example.com/System/Info/Public")
                client.get("http://47.112.219.60:19001/System/Info/Public")
                client.get("http://192.168.1.20/System/Info/Public")
                client.get("https://media.example.com/System/Info/Public")
            } finally {
                client.close()
            }

            assertEquals(4, engineCalls)
        }

    @Test
    fun embyClientRejectsCrossOriginHttpRedirectBeforeTheSecondSend() =
        runTest {
            var engineCalls = 0
            val client =
                createEmbyClient(
                    engine =
                        MockEngine {
                            engineCalls += 1
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers =
                                    headersOf(
                                        HttpHeaders.Location,
                                        "http://media.example.com/System/Info/Public",
                                    ),
                            )
                        },
                    appVersion = "1.0.0",
                    timeouts = null,
                )

            try {
                assertFailsWith<IllegalStateException> {
                    client.get("http://192.168.1.20/redirect")
                }
            } finally {
                client.close()
            }

            assertEquals(1, engineCalls)
        }

    @Test
    fun embyClientRejectsCrossOriginHttpsRedirectBeforeTheSecondSend() =
        runTest {
            var engineCalls = 0
            val client =
                createEmbyClient(
                    engine =
                        MockEngine {
                            engineCalls += 1
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "https://other.example/System/Info/Public"),
                            )
                        },
                    appVersion = "1.0.0",
                    timeouts = null,
                )

            try {
                assertFailsWith<IllegalStateException> {
                    client.get("https://media.example.com/redirect")
                }
            } finally {
                client.close()
            }

            assertEquals(1, engineCalls)
        }

    @Test
    fun embyClientAllowsSameOriginRedirect() =
        runTest {
            var engineCalls = 0
            val client =
                createEmbyClient(
                    engine =
                        MockEngine {
                            engineCalls += 1
                            if (engineCalls == 1) {
                                respond(
                                    content = "",
                                    status = HttpStatusCode.Found,
                                    headers = headersOf(HttpHeaders.Location, "/System/Info/Public"),
                                )
                            } else {
                                respond("{}", HttpStatusCode.OK)
                            }
                        },
                    appVersion = "1.0.0",
                    timeouts = null,
                )

            try {
                client.get("https://media.example.com/redirect")
            } finally {
                client.close()
            }

            assertEquals(2, engineCalls)
        }

    @Test
    fun authenticatedGetFallsBackToThePre060IdentityAfterA403AndRemembersIt() =
        runTest {
            val identities = mutableListOf<String>()
            val explicitClientNames = mutableListOf<String>()
            val client =
                createEmbyClient(
                    engine =
                        MockEngine { request ->
                            assertEquals("old-token", request.headers["X-Emby-Token"])
                            assertNull(request.headers[HttpHeaders.Authorization])
                            identities +=
                                assertNotNull(request.headers.getAll("X-Emby-Authorization")).single()
                            explicitClientNames += assertNotNull(request.headers["X-Emby-Client"])
                            if (identities.size == 1) {
                                respond("blocked", HttpStatusCode.Forbidden)
                            } else {
                                respond("{}", HttpStatusCode.OK)
                            }
                        },
                    appVersion = "1.0.0",
                    timeouts = null,
                )

            try {
                client.get("https://media.example.com/Users/u1/Views") {
                    header("X-Emby-Token", "old-token")
                }
                client.get("https://media.example.com/Users/u1/Views") {
                    header("X-Emby-Token", "old-token")
                }
                client.post("https://media.example.com/Sessions/Playing") {
                    header("X-Emby-Token", "old-token")
                }
            } finally {
                client.close()
            }

            assertEquals(4, identities.size)
            assertTrue(identities[0].contains("Client=\"Emby for Android Mobile\""))
            assertTrue(identities[1].contains("Client=\"Yfuse\""))
            assertTrue(identities[2].contains("Client=\"Yfuse\""))
            assertTrue(identities[3].contains("Client=\"Yfuse\""))
            assertEquals(
                listOf("Emby for Android Mobile", "Yfuse", "Yfuse", "Yfuse"),
                explicitClientNames,
            )
        }
}

@Serializable
private data class TestPayload(
    val value: Int,
)
