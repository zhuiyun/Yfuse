package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.RegisterRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WatchMultiDeviceTest {
    @Test
    fun phoneHostAndTabletViewerShareAccountWithIndependentPermissionsAndResume() =
        testApplication {
            val backend = AccountBackend.inMemoryForTests()
            val auth = backend.service.register(RegisterRequest("device-owner", "Watch-Test-42"))
            val other = backend.service.register(RegisterRequest("other-owner", "Watch-Test-42"))
            application { watchTogetherModule(accountBackend = backend, requireWatchAuthentication = true) }
            val sockets = createClient { install(WebSockets) }
            val phone =
                sockets.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                }
            phone.send("""{"type":"hello","protocolVersion":5,"clientId":"phone","mediaKey":"tmdb:351"}""")
            val phoneWelcome = phone.receiveType("welcome")
            val room = phoneWelcome.string("roomCode")
            val tablet =
                sockets.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                }
            tablet.send("""{"type":"hello","protocolVersion":5,"clientId":"tablet","roomCode":"$room"}""")
            val tabletWelcome = tablet.receiveType("welcome")
            assertTrue(phoneWelcome.getValue("isHost").jsonPrimitive.boolean)
            assertFalse(tabletWelcome.getValue("isHost").jsonPrimitive.boolean)
            assertFalse(tabletWelcome.getValue("canControl").jsonPrimitive.boolean)
            assertEquals(2, tabletWelcome.getValue("participantCount").jsonPrimitive.int)
            assertNotEquals(phoneWelcome.string("resumeCapability"), tabletWelcome.string("resumeCapability"))
            tablet.send("""{"type":"sync","positionMs":1000,"paused":false,"rate":1.0}""")
            assertEquals("当前没有播放控制权限", tablet.receiveType("error").string("message"))

            val conflicting =
                sockets.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${other.accessToken}")
                }
            conflicting.send("""{"type":"hello","protocolVersion":5,"clientId":"phone","roomCode":"$room"}""")
            assertEquals("account_membership_conflict", conflicting.receiveType("error").string("errorCode"))

            val replacement =
                sockets.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                }
            replacement.send(
                """{"type":"hello","protocolVersion":5,"clientId":"tablet","roomCode":"$room","resumeCapability":"${tabletWelcome.string(
                    "resumeCapability",
                )}"}""",
            )
            val resumed = replacement.receiveType("welcome")
            assertFalse(resumed.getValue("isHost").jsonPrimitive.boolean)
            assertEquals(2, resumed.getValue("participantCount").jsonPrimitive.int)
            assertEquals(
                "reconnected",
                withTimeout(2_000L) { (tablet as DefaultWebSocketSession).closeReason.await() }?.message,
            )
            phone.send("""{"type":"ping","clientSentAtMs":42}""")
            phone.receiveType("pong")
            replacement.close()
            phone.close()
        }

    @Test
    fun fullRoomRejectsOfflineResumeButAllowsOnlineHostReplacement() =
        testApplication {
            val backend = AccountBackend.inMemoryForTests()
            val auth = backend.service.register(RegisterRequest("full-room-owner", "Watch-Test-42"))
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    maxWatchConnectionsPerAccount = 20,
                )
            }
            val sockets = createClient { install(WebSockets) }
            val active = mutableListOf<WebSocketSession>()

            suspend fun connect(): WebSocketSession =
                sockets
                    .webSocketSession(
                        "/watch",
                    ) { headers.append(HttpHeaders.Authorization, "Bearer ${auth.accessToken}") }
                    .also { active.add(it) }
            val host = connect()
            host.send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:351"}""")
            val initial = host.receiveType("welcome")
            val room = initial.string("roomCode")
            val viewer = connect()
            viewer.send("""{"type":"hello","protocolVersion":5,"clientId":"offline","roomCode":"$room"}""")
            val old = viewer.receiveType("welcome")
            host.receiveType("roomUpdate")
            viewer.close()
            withTimeout(2_000L) {
                while (host
                        .receiveType("roomUpdate")
                        .getValue("participantCount")
                        .jsonPrimitive.int != 1
                ) {
                    Unit
                }
            }
            repeat(11) { index ->
                val guest = connect()
                guest.send("""{"type":"hello","protocolVersion":5,"clientId":"guest-$index","roomCode":"$room"}""")
                guest.receiveType("welcome")
            }
            val offlineResume = connect()
            offlineResume.send(
                """{"type":"hello","protocolVersion":5,"clientId":"offline","roomCode":"$room","resumeCapability":"${old.string(
                    "resumeCapability",
                )}"}""",
            )
            assertEquals("room_full", offlineResume.receiveType("error").string("errorCode"))
            val resumedHost = connect()
            resumedHost.send(
                """{"type":"hello","protocolVersion":5,"clientId":"host","roomCode":"$room","resumeCapability":"${initial.string(
                    "resumeCapability",
                )}","hostCapability":"${initial.string("hostCapability")}"}""",
            )
            val welcome = resumedHost.receiveType("welcome")
            assertTrue(welcome.getValue("isHost").jsonPrimitive.boolean)
            assertEquals(12, welcome.getValue("participantCount").jsonPrimitive.int)
            active.forEach { it.close() }
        }

    @Test
    fun removingOwnTabletDoesNotBanHostAccountFromReconnecting() =
        testApplication {
            val backend = AccountBackend.inMemoryForTests()
            val auth = backend.service.register(RegisterRequest("own-device-removal", "Watch-Test-42"))
            application { watchTogetherModule(accountBackend = backend, requireWatchAuthentication = true) }
            val sockets = createClient { install(WebSockets) }
            val phone =
                sockets.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                }
            phone.send("""{"type":"hello","protocolVersion":5,"clientId":"phone","mediaKey":"tmdb:351"}""")
            val initial = phone.receiveType("welcome")
            val room = initial.string("roomCode")
            val tablet =
                sockets.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                }
            tablet.send("""{"type":"hello","protocolVersion":5,"clientId":"tablet","roomCode":"$room"}""")
            tablet.receiveType("welcome")
            phone.send("""{"type":"kickParticipant","targetClientId":"tablet"}""")
            tablet.receiveType("kicked")
            val replacement =
                sockets.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                }
            replacement.send(
                """{"type":"hello","protocolVersion":5,"clientId":"phone","roomCode":"$room","resumeCapability":"${initial.string(
                    "resumeCapability",
                )}","hostCapability":"${initial.string("hostCapability")}"}""",
            )
            assertTrue(
                replacement
                    .receiveType("welcome")
                    .getValue("isHost")
                    .jsonPrimitive.boolean,
            )
            replacement.close()
            phone.close()
        }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private suspend fun WebSocketSession.receiveType(type: String): JsonObject =
        withTimeout(4_000L) {
            var found: JsonObject? = null
            while (found == null) {
                val payload = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                if (payload.string("type") == type) found = payload
            }
            found
        }
}
