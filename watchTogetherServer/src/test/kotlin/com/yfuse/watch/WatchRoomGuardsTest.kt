package com.yfuse.watch

import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchRoomGuardsTest {
    /** Every bearer is its own account, with one session per account. */
    private val accounts: suspend (String) -> com.yfuse.watch.account.AuthenticatedAccount = { token ->
        if (token.startsWith("user-")) {
            testWatchAccount(token.removePrefix("user-"))
        } else {
            throw AccountServiceException(AccountProblem.Unauthorized, "unauthorized", "unauthorized")
        }
    }

    private suspend fun ApplicationTestBuilder.socketAs(
        user: String,
        query: String = "",
    ): WebSocketSession =
        createClient { install(WebSockets) }.webSocketSession("/watch$query") {
            headers.append(HttpHeaders.Authorization, "Bearer user-$user")
        }

    private suspend fun WebSocketSession.hello(
        clientId: String,
        roomCode: String? = null,
        extra: String = "",
    ) {
        val room = roomCode?.let { ""","roomCode":"$it"""" } ?: ""","mediaKey":"tmdb:603""""
        send("""{"type":"hello","protocolVersion":6,"clientId":"$clientId"$room$extra}""")
    }

    @Test
    fun account_misses_survive_a_successful_join_and_only_the_address_is_forgiven() =
        testApplication {
            application {
                watchTogetherModule(
                    requireWatchAuthentication = true,
                    watchAccountAuthenticator = accounts,
                    watchAccountRevalidator = accounts,
                    joinFailureLimiter =
                        WatchJoinFailureLimiter(
                            maxFailures = 3,
                            windowMs = 60_000L,
                            penaltyMs = 60_000L,
                        ),
                    clientIpResolver = { call -> call.request.queryParameters["ip"] ?: "default" },
                )
            }
            val owner = socketAs("owner", "?ip=10.0.0.1")
            owner.hello("owner-phone")
            val code = assertNotNull(owner.awaitType("welcome").field("roomCode"))

            val guesser = socketAs("guesser", "?ip=10.0.0.2")
            guesser.hello("g", roomCode = "ZZZZZZ")
            guesser.awaitType("error")
            guesser.hello("g", roomCode = "ZZZZZY")
            guesser.awaitType("error")
            // Joining a real room clears the address's misses, not the account's.
            guesser.hello("g", roomCode = code)
            guesser.awaitType("welcome")

            val again = socketAs("guesser", "?ip=10.0.0.3")
            again.hello("g2", roomCode = "ZZZZZX")
            assertEquals("room_not_found", again.awaitType("error").field("errorCode"))
            again.hello("g2", roomCode = "ZZZZZW")
            assertEquals("join_rate_limited", again.awaitType("error").field("errorCode"))

            // Another account on the first guesser's address is not held back by those misses.
            val neighbour = socketAs("neighbour", "?ip=10.0.0.2")
            neighbour.hello("n", roomCode = "ZZZZZV")
            assertEquals("room_not_found", neighbour.awaitType("error").field("errorCode"))
            listOf(owner, guesser, again, neighbour).forEach { it.close() }
        }

    @Test
    fun only_a_member_from_before_the_host_left_inherits_the_host_seat() =
        testApplication {
            application { watchTogetherModule(hostGraceMs = 50L) }
            val sockets = createClient { install(WebSockets) }
            val host = sockets.webSocketSession("/watch")
            host.hello("host")
            val code = assertNotNull(host.awaitType("welcome").field("roomCode"))
            val member = sockets.webSocketSession("/watch")
            member.hello("member", roomCode = code)
            val resume = assertNotNull(member.awaitType("welcome").field("resumeCapability"))
            member.close()
            delay(100L)
            host.close()
            delay(200L)

            // Someone who only now found the code joins an empty room whose host left long ago.
            val newcomer = sockets.webSocketSession("/watch")
            newcomer.hello("newcomer", roomCode = code)
            assertFalse(
                newcomer
                    .awaitType("welcome")
                    .getValue("isHost")
                    .jsonPrimitive.boolean,
            )

            val returning = sockets.webSocketSession("/watch")
            returning.hello("member", roomCode = code, extra = ""","resumeCapability":"$resume"""")
            val welcome = returning.awaitType("welcome")
            assertTrue(welcome.getValue("isHost").jsonPrimitive.boolean)
            assertNotNull(welcome.field("hostCapability"))
            newcomer.close()
            returning.close()
        }

    @Test
    fun another_accounts_device_may_reuse_an_offline_id_but_not_one_online() =
        testApplication {
            application {
                watchTogetherModule(
                    requireWatchAuthentication = true,
                    watchAccountAuthenticator = accounts,
                    watchAccountRevalidator = accounts,
                )
            }
            val host = socketAs("alice")
            host.hello("alice-phone")
            val code = assertNotNull(host.awaitType("welcome").field("roomCode"))
            val carol = socketAs("carol")
            carol.hello("tablet-1", roomCode = code)
            val carolResume = assertNotNull(carol.awaitType("welcome").field("resumeCapability"))
            carol.close()
            host.awaitWhere("roomUpdate") { it.field("participantCount") == "1" }

            // The id was carol's; it no longer binds anyone else out of the room.
            val bob = socketAs("bob")
            bob.hello("tablet-1", roomCode = code)
            bob.awaitType("welcome")

            val carolAgain = socketAs("carol")
            carolAgain.hello("tablet-1", roomCode = code, extra = ""","resumeCapability":"$carolResume"""")
            val refused = carolAgain.awaitType("error")
            assertEquals("client_id_in_use", refused.field("errorCode"))
            assertEquals("true", refused.field("retryable"))
            val reason = withTimeout(2_000L) { (carolAgain as DefaultWebSocketSession).closeReason.await() }
            assertEquals(1013, reason?.code?.toInt())

            // Once bob's device leaves, carol's own membership (and capability) still works.
            bob.close()
            host.awaitWhere("roomUpdate") { it.field("participantCount") == "1" }
            val carolBack = socketAs("carol")
            carolBack.hello("tablet-1", roomCode = code, extra = ""","resumeCapability":"$carolResume"""")
            carolBack.awaitType("welcome")
            carolBack.close()
            host.close()
        }

    @Test
    fun rooms_and_memberships_survive_a_restart_of_the_relay() {
        val file = Files.createTempFile("watch-state", ".db").toFile()
        try {
            var code: String? = null
            var resume: String? = null
            var hostResume: String? = null
            var hostCapability: String? = null
            testApplication {
                application { watchTogetherModule(roomStateStore = SqliteWatchStateStore.sqlite(file)) }
                val sockets = createClient { install(WebSockets) }
                val host = sockets.webSocketSession("/watch")
                host.hello(
                    "host",
                    extra = ""","playlist":[{"id":"e1","mediaKey":"tmdb:603","title":"One"}]""",
                )
                val welcome = host.awaitType("welcome")
                code = welcome.field("roomCode")
                hostResume = welcome.field("resumeCapability")
                hostCapability = welcome.field("hostCapability")
                val guest = sockets.webSocketSession("/watch")
                guest.hello("guest", roomCode = code)
                resume = guest.awaitType("welcome").field("resumeCapability")
                host.send("""{"type":"sync","positionMs":42000,"paused":true,"rate":1.0}""")
                guest.awaitType("sync")
            }
            // A new process with the same database: the room comes back, members rejoin it.
            testApplication {
                application { watchTogetherModule(roomStateStore = SqliteWatchStateStore.sqlite(file)) }
                val sockets = createClient { install(WebSockets) }
                val guest = sockets.webSocketSession("/watch")
                guest.hello("guest", roomCode = code, extra = ""","resumeCapability":"$resume"""")
                val back = guest.awaitType("welcome")
                assertEquals(code, back.field("roomCode"))
                assertEquals("42000", back.field("positionMs"))
                assertEquals("1", back.field("participantCount"))
                assertFalse(back.getValue("isHost").jsonPrimitive.boolean)
                val host = sockets.webSocketSession("/watch")
                host.hello(
                    "host",
                    roomCode = code,
                    extra = ""","resumeCapability":"$hostResume","hostCapability":"$hostCapability"""",
                )
                assertTrue(
                    host
                        .awaitType("welcome")
                        .getValue("isHost")
                        .jsonPrimitive.boolean,
                )
                // A stranger without a capability is still just a new guest.
                val stranger = sockets.webSocketSession("/watch")
                stranger.hello("stranger", roomCode = code)
                assertFalse(
                    stranger
                        .awaitType("welcome")
                        .getValue("isHost")
                        .jsonPrimitive.boolean,
                )
            }
        } finally {
            file.delete()
            java.io.File(file.path + "-wal").delete()
            java.io.File(file.path + "-shm").delete()
        }
    }

    @Test
    fun rooms_older_than_the_restore_window_are_dropped_at_startup() {
        SqliteWatchStateStore.inMemory().use { store ->
            var now = 1_000_000L
            val persister = RoomStatePersister(store, restoreTtlMs = 60_000L, now = { now })
            val room =
                Room(
                    code = "ABC234",
                    creatorIp = "198.51.100.1",
                    creatorAccountUserId = "host-account",
                    hostId = "host",
                    hostCapabilityDigest = ByteArray(32),
                    timeline = Timeline("tmdb:603", 0L, 0L),
                )
            persister.sync(listOf(room))
            now += 59_000L
            assertEquals(
                listOf("ABC234"),
                RoomStatePersister(store, restoreTtlMs = 60_000L, now = {
                    now
                }).restore().map { it.code },
            )
            now += 2_000L
            assertTrue(RoomStatePersister(store, restoreTtlMs = 60_000L, now = { now }).restore().isEmpty())
        }
    }

    @Test
    fun emptied_rooms_are_released_without_waiting_for_the_next_hello() =
        testApplication {
            application { watchTogetherModule(roomGraceMs = 50L, roomSweepIntervalMs = 100L) }
            val sockets = createClient { install(WebSockets) }
            val host = sockets.webSocketSession("/watch")
            host.hello("host")
            host.awaitType("welcome")
            assertTrue(client.get("/watch/metrics").bodyAsText().contains("yfuse_watch_rooms_active 1"))
            host.close()
            withTimeout(3_000L) {
                while (!client.get("/watch/metrics").bodyAsText().contains("yfuse_watch_rooms_active 0")) delay(50L)
            }
        }
}
