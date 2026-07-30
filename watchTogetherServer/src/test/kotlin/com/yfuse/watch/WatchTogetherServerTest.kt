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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
    fun host_state_is_broadcast_to_joined_guest() = testApplication {
        application { watchTogetherModule() }
        val socketClient = createClient { install(WebSockets) }
        val roomCode = CompletableDeferred<String>()
        val guestJoined = CompletableDeferred<Unit>()
        val received = CompletableDeferred<String>()
        val testScope = CoroutineScope(currentCoroutineContext())

        val host = testScope.launch {
            socketClient.webSocket("/watch") {
                send(
                    """{"type":"create","clientId":"host","name":"Host","itemId":"tmdb:42"}""",
                )
                val joined = (incoming.receive() as Frame.Text).readText()
                roomCode.complete(
                    Json.parseToJsonElement(joined).jsonObject["roomCode"]!!.jsonPrimitive.content,
                )
                guestJoined.await()
                send(
                    """{"type":"playback","itemId":"tmdb:42","itemIndex":3,"positionMs":45678,"playing":false,"sentAtEpochMs":123}""",
                )
                received.await()
            }
        }
        val guest = testScope.launch {
            val code = roomCode.await()
            socketClient.webSocket("/watch") {
                send(
                    """{"type":"join","roomCode":"$code","clientId":"guest","name":"Guest","itemId":"tmdb:42"}""",
                )
                while (true) {
                    val raw = (incoming.receive() as Frame.Text).readText()
                    val payload = Json.parseToJsonElement(raw).jsonObject
                    when (payload["type"]?.jsonPrimitive?.content) {
                        "joined" -> guestJoined.complete(Unit)
                        "playback" -> {
                            received.complete(raw)
                            break
                        }
                    }
                }
            }
        }

        val payload = Json.parseToJsonElement(
            withTimeout(5_000L) { received.await() },
        ).jsonObject
        assertEquals(3, payload["itemIndex"]?.jsonPrimitive?.int)
        assertEquals(45_678L, payload["positionMs"]?.jsonPrimitive?.long)
        assertFalse(payload["playing"]!!.jsonPrimitive.boolean)

        host.cancelAndJoin()
        guest.cancelAndJoin()
    }
}
