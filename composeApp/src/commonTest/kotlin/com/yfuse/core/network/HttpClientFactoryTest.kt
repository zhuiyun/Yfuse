package com.yfuse.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HttpClientFactoryTest {

    @Test
    fun danmaku_client_installs_timeout_protection() {
        val client = createDanmakuClient(MockEngine { respond("{}", HttpStatusCode.OK) })

        try {
            assertNotNull(client.pluginOrNull(HttpTimeout))
        } finally {
            client.close()
        }
    }

    @Test
    fun emby_identity_header_uses_the_injected_build_version() = runTest {
        var authorization: String? = null
        val client = createEmbyClient(
            engine = MockEngine { request ->
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

        assertTrue(assertNotNull(authorization).contains("Version=\"9.8.7\""))
    }
}
