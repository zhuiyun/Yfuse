package com.yfuse.watch

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarScheduleRoutesTest {
    @Test
    fun exposes_redacted_ingestion_health_without_a_signing_key() =
        testApplication {
            application { routing { calendarScheduleRoutes(null) } }

            val response = client.get("/api/v1/calendar/ingestion/status")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"state\""))
            assertTrue(response.bodyAsText().contains("\"configuredShows\""))
        }

    @Test
    fun unavailableWithoutSigningKey() =
        testApplication {
            application { routing { calendarScheduleRoutes(null) } }

            val response = client.get("/api/v1/calendar/schedules")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("calendar_signing_unavailable"))
        }

    @Test
    fun servesPayloadWithVerifiableEd25519Signature() =
        testApplication {
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val signer =
                CalendarScheduleSigner.fromPkcs8Base64(
                    Base64.getEncoder().encodeToString(keyPair.private.encoded),
                )
            application { routing { calendarScheduleRoutes(signer) } }

            val response = client.get("/api/v1/calendar/schedules")
            val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val payload = envelope.getValue("payload").jsonPrimitive.content
            val signature = Base64.getDecoder().decode(envelope.getValue("signature").jsonPrimitive.content)
            val verified =
                Signature.getInstance("Ed25519").run {
                    initVerify(keyPair.public)
                    update(payload.encodeToByteArray())
                    verify(signature)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(verified)
            assertTrue(payload.contains("师兄太稳健"))
            assertTrue(payload.contains("2026-08-30"))
            assertTrue(payload.contains("\"confidence\":100"))
            assertTrue(payload.contains("\"type\":\"VerifiedAccount\""))
        }
}
