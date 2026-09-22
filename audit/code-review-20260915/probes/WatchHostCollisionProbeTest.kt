package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.RegisterRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Audit reproduction: these assertions demonstrate the current defect, not the desired policy. */
class WatchHostCollisionProbeTest {
    @Test
    fun second_account_can_claim_public_host_client_id_without_host_capability() =
        testApplication {
            val backend = AccountBackend.inMemoryForTests()
            val hostAuth = backend.execute {
                register(RegisterRequest(username = "audit-host", password = "Audit-Password-42"))
            }
            val otherAuth = backend.execute {
                register(RegisterRequest(username = "audit-other", password = "Audit-Password-43"))
            }
            assertNotEquals(hostAuth.user.id, otherAuth.user.id)
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                )
            }
            val socketClient = createClient { install(WebSockets) }
            val host = socketClient.webSocketSession("/watch") {
                headers.append(HttpHeaders.Authorization, "Bearer ${hostAuth.accessToken}")
            }
            try {
                host.send(
                    """{"type":"hello","protocolVersion":5,"clientId":"public-host-client","mediaKey":"tmdb:42"}""",
                )
                val hostWelcome = host.awaitAuditMessage("welcome")
                val roomCode = hostWelcome.getValue("roomCode").jsonPrimitive.content
                val publicHostId = hostWelcome.getValue("participants").jsonArray
                    .single().jsonObject.getValue("clientId").jsonPrimitive.content
                assertTrue(hostWelcome.getValue("isHost").jsonPrimitive.boolean)

                val other = socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${otherAuth.accessToken}")
                }
                try {
                    // Both capability fields are deliberately omitted. Only a room code and
                    // the public client ID are supplied, while the bearer belongs to account B.
                    other.send(
                        """{"type":"hello","protocolVersion":5,"clientId":"$publicHostId","roomCode":"$roomCode"}""",
                    )
                    val stolenWelcome = other.awaitAuditMessage("welcome")
                    assertTrue(stolenWelcome.getValue("isHost").jsonPrimitive.boolean)
                    assertTrue(stolenWelcome.getValue("canControl").jsonPrimitive.boolean)

                    // Prove effective authority, rather than only trusting the welcome flags.
                    other.send(
                        """{"type":"sync","positionMs":12345,"paused":false,"rate":1.0}""",
                    )
                    val sync = other.awaitAuditMessage("sync")
                    assertEquals(12345L, sync.getValue("positionMs").jsonPrimitive.long)
                    assertEquals(1L, sync.getValue("seq").jsonPrimitive.long)
                } finally {
                    other.close()
                }
            } finally {
                host.close()
            }
        }
}

private suspend fun WebSocketSession.awaitAuditMessage(type: String): JsonObject =
    withTimeout(5_000L) {
        var selected: JsonObject? = null
        while (selected == null) {
            val frame = incoming.receive()
            if (frame is Frame.Text) {
                val message = Json.parseToJsonElement(frame.readText()).jsonObject
                val actualType = message["type"]?.jsonPrimitive?.content
                check(actualType != "error") { "Unexpected error response: $message" }
                if (actualType == type) selected = message
            }
        }
        selected
    }
