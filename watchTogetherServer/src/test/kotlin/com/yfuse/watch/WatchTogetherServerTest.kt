package com.yfuse.watch

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class WatchTogetherServerTest {
    @Test
    fun health_and_update_files_share_the_watch_server() {
        val updateRoot = Files.createTempDirectory("yfuse-watch-test").toFile()
        try {
            updateRoot.resolve("update.json").writeText("""{"versionCode":29}""")
            testApplication {
                application { watchTogetherModule(updateRoot) }

                val health = client.get("/health")
                assertEquals(HttpStatusCode.OK, health.status)
                assertEquals("ok", health.bodyAsText())

                val update = client.get("/yfuse/update.json")
                assertEquals(HttpStatusCode.OK, update.status)
                assertEquals("""{"versionCode":29}""", update.bodyAsText())
            }
        } finally {
            updateRoot.deleteRecursively()
        }
    }

    @Test
    fun host_sync_is_broadcast_to_joined_guest() = testApplication {
        application { watchTogetherModule() }
        val socketClient = createClient { install(WebSockets) }
        val roomCode = CompletableDeferred<String>()
        val guestJoined = CompletableDeferred<Unit>()
        val received = CompletableDeferred<String>()
        val testScope = CoroutineScope(currentCoroutineContext())

        val host = testScope.launch {
            socketClient.webSocket("/watch") {
                send("""{"type":"hello","clientId":"host","name":"Host","mediaKey":"tmdb:42"}""")
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)
                assertTrue(welcome["isHost"]!!.jsonPrimitive.boolean)
                roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)

                guestJoined.await()
                send(
                    """{"type":"sync","mediaKey":"tmdb:42","positionMs":45678,"paused":false,"rate":1.0}""",
                )
                received.await()
            }
        }
        val guest = testScope.launch {
            val code = roomCode.await()
            socketClient.webSocket("/watch") {
                send("""{"type":"hello","roomCode":"$code","clientId":"guest","name":"Guest"}""")
                while (true) {
                    val payload = (incoming.receive() as Frame.Text).readText().asJson()
                    when (payload["type"]?.jsonPrimitive?.content) {
                        "welcome" -> {
                            assertFalse(payload["isHost"]!!.jsonPrimitive.boolean)
                            guestJoined.complete(Unit)
                        }
                        "sync" -> {
                            received.complete(payload.toString())
                            break
                        }
                    }
                }
            }
        }

        val payload = Json.parseToJsonElement(
            withTimeout(5_000L) { received.await() },
        ).jsonObject
        assertEquals(45_678L, payload["positionMs"]?.jsonPrimitive?.long)
        assertFalse(payload["paused"]!!.jsonPrimitive.boolean)
        assertEquals(1, payload["seq"]?.jsonPrimitive?.int)
        assertTrue(payload["anchorAtMs"]?.jsonPrimitive?.long != null)

        host.cancelAndJoin()
        guest.cancelAndJoin()
    }

    @Test
    fun guest_sync_is_rejected() = testApplication {
        application { watchTogetherModule() }
        val socketClient = createClient { install(WebSockets) }
        val roomCode = CompletableDeferred<String>()
        val guestReady = CompletableDeferred<Unit>()
        val errorReceived = CompletableDeferred<String>()
        val testScope = CoroutineScope(currentCoroutineContext())

        val host = testScope.launch {
            socketClient.webSocket("/watch") {
                send("""{"type":"hello","clientId":"host","mediaKey":"tmdb:1"}""")
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                errorReceived.await()
            }
        }
        val guest = testScope.launch {
            val code = roomCode.await()
            socketClient.webSocket("/watch") {
                send("""{"type":"hello","roomCode":"$code","clientId":"guest"}""")
                incoming.receive() // welcome
                guestReady.complete(Unit)
                send("""{"type":"sync","mediaKey":"tmdb:1","positionMs":1,"paused":false,"rate":1.0}""")
                // A `roomUpdate` broadcast (triggered by this guest's own join) can land
                // before the rejection — drain past it.
                var reply = (incoming.receive() as Frame.Text).readText().asJson()
                while (reply["type"]?.jsonPrimitive?.content != "error") {
                    reply = (incoming.receive() as Frame.Text).readText().asJson()
                }
                errorReceived.complete(reply["message"]!!.jsonPrimitive.content)
            }
        }

        val message = withTimeout(5_000L) { errorReceived.await() }
        assertTrue(message.contains("房主"))
        host.cancelAndJoin()
        guest.cancelAndJoin()
    }

    @Test
    fun disconnected_host_is_replaced_when_a_guest_remains() = testApplication {
        application { watchTogetherModule(hostGraceMs = 25L) }
        val socketClient = createClient { install(WebSockets) }
        val roomCode = CompletableDeferred<String>()
        val guestJoined = CompletableDeferred<Unit>()
        val guestPromoted = CompletableDeferred<Boolean>()
        val testScope = CoroutineScope(currentCoroutineContext())

        val host = testScope.launch {
            socketClient.webSocket("/watch") {
                send("""{"type":"hello","clientId":"host","mediaKey":"tmdb:7"}""")
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                guestJoined.await()
                // Falling off the end here closes the connection — same as a network
                // drop. The guest was already present, so the server should hand host off
                // after the deliberately short reconnect grace configured for this test.
            }
        }

        val guest = testScope.launch {
            val code = roomCode.await()
            socketClient.webSocket("/watch") {
                send("""{"type":"hello","roomCode":"$code","clientId":"guest"}""")
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                assertFalse(welcome["isHost"]!!.jsonPrimitive.boolean)
                guestJoined.complete(Unit)
                while (true) {
                    val payload = (incoming.receive() as Frame.Text).readText().asJson()
                    if (payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
                        payload["isHost"]?.jsonPrimitive?.boolean == true
                    ) {
                        guestPromoted.complete(true)
                        break
                    }
                }
            }
        }

        assertTrue(withTimeout(5_000L) { guestPromoted.await() })
        host.cancelAndJoin()
        guest.cancelAndJoin()
    }

    @Test
    fun reconnect_with_same_client_id_resumes_as_host_without_a_new_room() = testApplication {
        application { watchTogetherModule() }
        val socketClient = createClient { install(WebSockets) }
        val roomCode = CompletableDeferred<String>()

        socketClient.webSocket("/watch") {
            send("""{"type":"hello","clientId":"host","mediaKey":"tmdb:9"}""")
            val welcome = (incoming.receive() as Frame.Text).readText().asJson()
            roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
            // Close without an explicit leave message — same as a network drop.
        }

        val code = roomCode.await()
        socketClient.webSocket("/watch") {
            send("""{"type":"hello","roomCode":"$code","clientId":"host"}""")
            val welcome = (incoming.receive() as Frame.Text).readText().asJson()
            assertEquals(code, welcome["roomCode"]?.jsonPrimitive?.content)
            assertTrue(welcome["isHost"]!!.jsonPrimitive.boolean)
            assertNotEquals("null", welcome["mediaKey"].toString())
        }
    }
}

private fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
