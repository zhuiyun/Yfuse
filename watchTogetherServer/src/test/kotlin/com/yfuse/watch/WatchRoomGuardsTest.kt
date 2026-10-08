package com.yfuse.watch

import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
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
}
