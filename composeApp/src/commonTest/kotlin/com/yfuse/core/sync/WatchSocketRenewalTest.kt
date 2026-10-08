package com.yfuse.core.sync

import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class WatchSocketRenewalTest {
    @Test
    fun a_socket_that_hears_nothing_for_three_ping_intervals_is_dead() {
        val time = TestTimeSource()
        val liveness = WatchSocketLiveness(intervalMs = 8_000L, missedIntervals = 3, timeSource = time)
        time += 23.seconds
        assertFalse(liveness.isStale())
        liveness.heard()
        time += 23.seconds
        assertFalse(liveness.isStale())
        time += 1.seconds
        assertTrue(liveness.isStale())
    }

    @Test
    fun renewal_sends_a_token_refreshed_elsewhere_before_refreshing_itself() =
        runTest {
            var current = "first"
            var refreshes = 0
            val renewal =
                WatchAccessRenewal(
                    initialToken = "first",
                    currentToken = { current },
                    refreshToken = {
                        refreshes++
                        "refreshed-$refreshes"
                    },
                    leadMs = 60_000L,
                    timeSource = TestTimeSource(),
                )
            val sent = Channel<WatchWireMessage>(Channel.UNLIMITED)
            backgroundScope.launch { renewal.run { sent.send(it) } }

            // The socket's access lapses inside the lead: renew at once, with a fresh token.
            renewal.renewed(authExpiresAtMs = 1_030_000L, serverAtMs = 1_000_000L)
            val first = sent.receive()
            assertEquals("reauthenticate", first.type)
            assertEquals("refreshed-1", first.credential?.accessToken)

            // The relay found the token replaced: what the account already holds goes out as it is.
            current = "rotated-elsewhere"
            renewal.required()
            assertEquals("rotated-elsewhere", sent.receive().credential?.accessToken)
            assertEquals(1, refreshes)
        }

    @Test
    fun only_reauth_errors_reach_the_renewal() {
        val renewal = WatchAccessRenewal("token", { "token" }, { "next" })
        assertFalse(renewal.handles(WatchWireMessage(type = "error", errorCode = "chat_rate_limited")))
        assertFalse(renewal.handles(WatchWireMessage(type = "error")))
        assertTrue(renewal.handles(WatchWireMessage(type = "error", errorCode = "reauth_mismatch")))
        assertTrue(renewal.handles(WatchWireMessage(type = "error", errorCode = "reauth_required")))
    }

    @Test
    fun the_app_declares_room_revisions_and_renewal_in_its_hello() {
        assertTrue("roomRevision" in WATCH_CLIENT_CAPABILITIES)
        assertTrue("reauthenticate" in WATCH_CLIENT_CAPABILITIES)
    }
}
