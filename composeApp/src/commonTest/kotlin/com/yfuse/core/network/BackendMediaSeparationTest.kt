package com.yfuse.core.network

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendEndpoints
import com.yfuse.backend.BackendUnavailableException
import com.yfuse.core.account.createAccountClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendMediaSeparationTest {
    @Test
    fun disabling_owned_backend_does_not_block_media_server_on_the_same_host() =
        runTest {
            var backendRequests = 0
            var mediaRequests = 0
            val backend =
                createAccountClient(
                    MockEngine {
                        backendRequests++
                        respond("{}")
                    },
                    access = BackendAccess(false),
                )
            val media =
                createEmbyClient(
                    "test",
                    MockEngine {
                        mediaRequests++
                        respond("{}")
                    },
                    timeouts = null,
                )
            try {
                assertFailsWith<BackendUnavailableException> { backend.get(BackendEndpoints.CALENDAR) }
                val mediaOrigin = BackendEndpoints.ORIGIN.replace("https://", "http://") + ":19001"
                assertEquals(HttpStatusCode.OK, media.get("$mediaOrigin/System/Info/Public").status)
                assertEquals(0, backendRequests)
                assertEquals(1, mediaRequests)
            } finally {
                backend.close()
                media.close()
            }
        }
}
