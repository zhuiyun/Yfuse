package com.yfuse.watch

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarScheduleDatabaseRoutesTest {
    @Test
    fun serves_database_revision_and_honors_etag() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signer =
            CalendarScheduleSigner.fromPkcs8Base64(
                Base64.getEncoder().encodeToString(keyPair.private.encoded),
            )
        val publication =
            CalendarPublication(
                revision = "2026-08-27-r3",
                generatedAt = "2026-08-27T05:00:00Z",
                schedules =
                    DEFAULT_CALENDAR_SCHEDULES.map { schedule ->
                        schedule.copy(revision = "2026-08-27-r3")
                    },
            )

        CalendarScheduleStore.inMemory().use { store ->
            assertTrue(store.replace(publication))
            testApplication {
                application {
                    routing {
                        calendarScheduleRoutes(signer, store)
                    }
                }

                val first = client.get("/api/v1/calendar/schedules")
                val etag = first.headers[HttpHeaders.ETag]
                val unchanged =
                    client.get("/api/v1/calendar/schedules") {
                        header(HttpHeaders.IfNoneMatch, etag)
                    }

                assertEquals(HttpStatusCode.OK, first.status)
                assertEquals("\"calendar-2026-08-27-r3\"", etag)
                assertTrue(first.bodyAsText().contains("2026-08-27-r3"))
                assertEquals(HttpStatusCode.NotModified, unchanged.status)
            }
        }
    }
}
