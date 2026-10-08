package com.yfuse.watch

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchRoomUpdateTest {
    private suspend fun ApplicationTestBuilder.socket(): WebSocketSession =
        createClient { install(WebSockets) }.webSocketSession("/watch")

    private suspend fun WebSocketSession.hello(
        clientId: String,
        roomCode: String? = null,
        extra: String = "",
    ) {
        val room = roomCode?.let { ""","roomCode":"$it"""" } ?: ""","mediaKey":"tmdb:603""""
        send("""{"type":"hello","protocolVersion":6,"clientId":"$clientId"$room$extra}""")
    }

    @Test
    fun snapshots_carry_rising_revisions_and_skip_an_unchanged_playlist_for_clients_that_ask() =
        testApplication {
            application { watchTogetherModule() }
            val host = socket()
            host.hello(
                "host",
                extra =
                    ""","capabilities":["roomRevision"],"playlist":[""" +
                        """{"id":"e1","mediaKey":"tmdb:603","title":"One"},{"id":"e2","mediaKey":"tmdb:604","title":"Two"}]""",
            )
            val welcome = host.awaitType("welcome")
            val code = assertNotNull(welcome.field("roomCode"))
            val welcomeRevision = welcome.getValue("roomRevision").jsonPrimitive.long
            assertEquals(2, welcome.getValue("playlist").jsonArray.size)

            val legacy = socket()
            legacy.hello("legacy", roomCode = code)
            legacy.awaitType("welcome")

            // The newer host already has this playlist; the older guest gets it every time.
            val hostUpdate = host.awaitWhere("roomUpdate") { it.field("participantCount") == "2" }
            assertFalse("playlist" in hostUpdate)
            assertFalse("playlistRevision" in hostUpdate)
            val hostRevision = hostUpdate.getValue("roomRevision").jsonPrimitive.long
            assertTrue(hostRevision > welcomeRevision)
            val legacyUpdate = legacy.awaitType("roomUpdate")
            assertEquals(2, legacyUpdate.getValue("playlist").jsonArray.size)

            host.send(
                """{"type":"playlistAdd","playlistRevision":0,"playlistEntry":{"id":"e3","mediaKey":"tmdb:605","title":"Three"}}""",
            )
            val moved = host.awaitWhere("roomUpdate") { "playlist" in it }
            assertEquals(3, moved.getValue("playlist").jsonArray.size)
            assertEquals(1L, moved.getValue("playlistRevision").jsonPrimitive.long)
            assertTrue(moved.getValue("roomRevision").jsonPrimitive.long > hostRevision)
            val legacyMoved = legacy.awaitWhere("roomUpdate") { it.field("playlistRevision") == "1" }
            assertEquals(3, legacyMoved.getValue("playlist").jsonArray.size)
            legacy.close()
            host.close()
        }

    @Test
    fun a_burst_of_readiness_flips_is_merged_into_a_few_updates_that_end_on_the_last_state() =
        testApplication {
            application { watchTogetherModule(roomUpdateMinIntervalMs = 300L) }
            val host = socket()
            host.hello("host")
            val code = assertNotNull(host.awaitType("welcome").field("roomCode"))
            val guest = socket()
            guest.hello("guest", roomCode = code)
            guest.awaitType("welcome")
            host.awaitType("roomUpdate")
            delay(400L)

            repeat(10) { index ->
                val ready = index % 2 == 0
                guest.send("""{"type":"playbackStatus","ready":$ready,"buffering":false,"mediaAvailable":true}""")
            }
            val updates = host.drainFor(1_500L).filter { it.field("type") == "roomUpdate" }
            assertTrue(updates.isNotEmpty())
            assertTrue(updates.size < 10, "expected merged updates, got ${updates.size}")
            val revisions = updates.map { it.getValue("roomRevision").jsonPrimitive.long }
            assertEquals(revisions.sorted(), revisions)
            val lastGuest =
                updates
                    .last()
                    .getValue("participants")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.field("clientId") == "guest" }
            // The tenth flip (index 9) said not ready; false is left out of the wire form.
            assertFalse(lastGuest["ready"]?.jsonPrimitive?.boolean ?: false)
            guest.close()
            host.close()
        }

    @Test
    fun anchors_past_the_sync_pace_reach_the_room_as_one_trailing_sync() =
        testApplication {
            application { watchTogetherModule() }
            val host = socket()
            host.hello("host")
            val code = assertNotNull(host.awaitType("welcome").field("roomCode"))
            val guest = socket()
            guest.hello("guest", roomCode = code)
            guest.awaitType("welcome")

            repeat(PacedAction.Sync.maxPerWindow + 5) { index ->
                host.send("""{"type":"sync","positionMs":${1_000 + index},"paused":false,"rate":1.0}""")
            }
            val syncs = guest.drainFor(2_500L).filter { it.field("type") == "sync" }
            assertTrue(syncs.size <= PacedAction.Sync.maxPerWindow + 1, "got ${syncs.size} syncs")
            val last = PacedAction.Sync.maxPerWindow + 4
            assertEquals(
                (1_000 + last).toLong(),
                syncs
                    .last()
                    .getValue("positionMs")
                    .jsonPrimitive.long,
            )
            guest.close()
            host.close()
        }

    @Test
    fun errors_carry_stable_codes_and_whether_to_retry() =
        testApplication {
            application {
                watchTogetherModule(
                    joinFailureLimiter =
                        WatchJoinFailureLimiter(
                            maxFailures = 2,
                            windowMs = 60_000L,
                            penaltyMs = 60_000L,
                        ),
                )
            }
            val guesser = socket()
            guesser.hello("guesser", roomCode = "ZZZZZZ")
            val missing = guesser.awaitType("error")
            assertEquals("room_not_found", missing.field("errorCode"))
            assertEquals("false", missing.field("retryable"))
            guesser.hello("guesser", roomCode = "ZZZZZY")
            guesser.awaitType("error")
            guesser.hello("guesser", roomCode = "ZZZZZX")
            val limited = guesser.awaitType("error")
            assertEquals("join_rate_limited", limited.field("errorCode"))
            assertEquals("true", limited.field("retryable"))
            guesser.close()
        }

    @Test
    fun a_full_room_closes_the_socket_instead_of_leaving_it_to_probe_more_codes() =
        testApplication {
            application { watchTogetherModule() }
            val host = socket()
            host.hello("host")
            val code = assertNotNull(host.awaitType("welcome").field("roomCode"))
            val members = (1 until 12).map { index -> socket().also { it.hello("guest-$index", roomCode = code) } }
            members.forEach { it.awaitType("welcome") }
            val late = socket()
            late.hello("late", roomCode = code)
            assertEquals("room_full", late.awaitType("error").field("errorCode"))
            val reason = withTimeout(2_000L) { (late as DefaultWebSocketSession).closeReason.await() }
            assertEquals(1013, reason?.code?.toInt())
            members.forEach { it.close() }
            host.close()
        }

    @Test
    fun outbound_budget_runs_into_debt_and_refills_over_time() {
        val budget = RoomOutboundBudget(burstBytes = 1_000L, bytesPerSecond = 1_000L)
        assertEquals(0L, budget.waitMs(0L))
        budget.spend(3_000L, 0L)
        // 2,000 bytes of debt at 1,000 bytes a second.
        assertTrue(budget.waitMs(0L) in 2_000L..2_001L)
        assertTrue(budget.waitMs(1_000L) in 1_000L..1_001L)
        assertEquals(0L, budget.waitMs(2_100L))
    }

    @Test
    fun pacer_admits_up_to_the_window_then_refuses_until_it_slides() {
        var now = 0L
        val pacer = WatchMessagePacer { now }
        repeat(PacedAction.Control.maxPerWindow) { assertTrue(pacer.admit(PacedAction.Control)) }
        assertFalse(pacer.admit(PacedAction.Control))
        // Another kind keeps its own window.
        assertTrue(pacer.admit(PacedAction.Playlist))
        now = PacedAction.Control.windowMs
        assertTrue(pacer.admit(PacedAction.Control))
    }
}
