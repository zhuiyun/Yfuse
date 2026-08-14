package com.yfuse.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun embyIdentityHeaderUsesTheInjectedBuildVersion() =
        runTest {
            var authorization: String? = null
            val client =
                createEmbyClient(
                    engine =
                        MockEngine { request ->
                            authorization = request.headers["X-Emby-Authorization"]
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
        }

    @Test
    fun embyClientBlocksPublicHttpBeforeTheEngine() =
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
                assertFailsWith<IllegalStateException> {
                    client.get("http://media.example.com/System/Info/Public")
                }
            } finally {
                client.close()
            }

            assertEquals(0, engineCalls)
        }

    @Test
    fun embyClientAllowsHttpsAndLocalHttp() =
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
                client.get("https://media.example.com/System/Info/Public")
                client.get("http://192.168.1.20/System/Info/Public")
            } finally {
                client.close()
            }

            assertEquals(2, engineCalls)
        }

    @Test
    fun embyClientRechecksRedirectTargetsBeforeSending() =
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
}
