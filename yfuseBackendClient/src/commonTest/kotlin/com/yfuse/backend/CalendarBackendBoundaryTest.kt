package com.yfuse.backend

import com.yfuse.core.account.createBackendAccountClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CalendarBackendBoundaryTest {
    @Test
    fun disabled_calendar_never_dispatches_even_with_enabled_client() =
        runTest {
            var requests = 0
            val client =
                createBackendAccountClient(
                    MockEngine {
                        requests++
                        respond("{}")
                    },
                    BackendAccess(true),
                )
            try {
                assertFailsWith<BackendUnavailableException> {
                    HttpCalendarBackendApi(client, access = BackendAccess(false)).fetch("saved-revision")
                }
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun unchanged_calendar_sends_revision_without_account_credentials() =
        runTest {
            val client =
                createBackendAccountClient(
                    MockEngine { request ->
                        assertEquals("\"calendar-r2\"", request.headers[HttpHeaders.IfNoneMatch])
                        assertNull(request.headers[HttpHeaders.Authorization])
                        respond("", HttpStatusCode.NotModified)
                    },
                    BackendAccess(true),
                )
            try {
                val publication =
                    withContext(Dispatchers.Default) {
                        HttpCalendarBackendApi(client, access = BackendAccess(true)).fetch("r2")
                    }
                assertNull(publication)
            } finally {
                client.close()
            }
        }

    @Test
    fun disabled_backend_client_rejects_direct_requests_before_engine_dispatch() =
        runTest {
            var requests = 0
            val client =
                createBackendAccountClient(
                    MockEngine {
                        requests++
                        respond("{}")
                    },
                    BackendAccess(false),
                )
            try {
                assertFailsWith<BackendUnavailableException> { client.get(BackendEndpoints.CALENDAR) }
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun default_access_tracks_generated_build_configuration() {
        assertEquals(BackendBuildConfig.ENABLED, BackendAccess.Default.enabled)
        assertEquals(BackendBuildConfig.ENABLED, BackendAccess().enabled)
    }
}
