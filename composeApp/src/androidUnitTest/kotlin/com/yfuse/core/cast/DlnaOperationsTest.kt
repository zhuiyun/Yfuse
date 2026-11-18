package com.yfuse.core.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaOperationsTest {
    @Test
    fun user_seek_supersedes_a_handoff_in_flight_and_an_older_queued_gesture() =
        runTest {
            val commands = DlnaSeekCommands()
            val sent = mutableListOf<Int>()
            val entered = CompletableDeferred<Unit>()
            val received = CompletableDeferred<Unit>()
            val handoff =
                async {
                    commands.whenCurrent(0L) {
                        sent += 11
                        entered.complete(Unit)
                        received.await()
                        11
                    }
                }
            entered.await()
            val forward = commands.select(60_000L, 1_000L)
            val queued = async { commands.whenCurrent(forward) { sent.add(60) } }
            val back = commands.select(20_000L, 2_000L)
            val latest = async { commands.whenCurrent(back) { sent.add(20) } }
            received.complete(Unit)
            assertNull(handoff.await())
            assertNull(queued.await())
            assertEquals(true, latest.await())
            assertNull(commands.whenCurrent(0L) { sent.add(11) })
            assertEquals(listOf(11, 20), sent)
        }

    @Test
    fun paused_clock_must_be_reachable_from_the_last_gesture() {
        val commands = DlnaSeekCommands()
        assertFalse(commands.matchesPausedClock(21_000L, 1_000L, 2_000L))
        commands.select(20_000L, 3_000L)
        assertTrue(commands.matchesPausedClock(21_000L, 4_500L, 2_000L))
        assertFalse(commands.matchesPausedClock(95_000L, 4_500L, 2_000L))
        assertFalse(commands.matchesPausedClock(11_000L, 4_500L, 2_000L))
        assertFalse(commands.matchesPausedClock(null, 4_500L, 2_000L))
    }

    @Test
    fun resume_restores_only_a_corroborated_paused_position_on_an_untrusted_seekable_renderer() {
        val commands = DlnaSeekCommands()
        commands.select(20_000L, 3_000L)
        val paused =
            CastState(
                status = CastPlaybackStatus.Paused,
                positionConfirmed = true,
                positionMs = 21_000L,
                capabilities = CastCapabilities(seek = CastCapability.Supported),
            )
        assertNull(commands.resumeAnchor(paused, false))
        assertEquals(21_000L, commands.observeUntrustedClock(CastPlaybackStatus.Paused, 21_000L, 4_500L, 2_000L))
        assertEquals(21_000L, commands.resumeAnchor(paused, false))
        assertNull(commands.resumeAnchor(paused, true))
        assertNull(commands.resumeAnchor(paused.copy(positionConfirmed = false), false))
        assertNull(commands.resumeAnchor(paused.copy(capabilities = CastCapabilities()), false))
        assertNull(commands.observeUntrustedClock(CastPlaybackStatus.Paused, 95_000L, 100_000L, 2_000L))
        assertEquals(21_000L, commands.resumeAnchor(paused, false))
        commands.observeUntrustedClock(CastPlaybackStatus.Playing, 95_000L, 100_100L, 2_000L)
        assertNull(commands.resumeAnchor(paused, false))
        commands.observeUntrustedClock(CastPlaybackStatus.Paused, 22_000L, 101_000L, 2_000L)
        commands.select(60_000L, 102_000L)
        assertNull(commands.resumeAnchor(paused.copy(positionMs = 22_000L), false))
    }

    @Test
    fun confirmed_seek_returns_before_an_unnecessary_failing_request() =
        runTest {
            var requests = 0
            val confirmed =
                awaitDlnaConfirmation(
                    attempts = 3,
                    delayMs = 300L,
                    read = {
                        requests++
                        if (requests > 1) throw IOException("receiver unavailable after the successful seek")
                        12_000L
                    },
                    accepted = { it == 12_000L },
                )
            assertEquals(12_000L, confirmed)
            assertEquals(1, requests)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun unconfirmed_receiver_uses_only_the_bounded_retry_budget() =
        runTest {
            var requests = 0
            assertNull(awaitDlnaConfirmation(3, 300L, read = { ++requests }, accepted = { false }))
            assertEquals(3, requests)
            assertEquals(600L, testScheduler.currentTime)
        }

    @Test
    fun cancelled_blocking_read_cannot_publish_success_after_switching_receivers() =
        runBlocking {
            val current = AtomicReference(playing("a"))
            val token = current.get().castSessionToken()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val operation =
                async(Dispatchers.Default) {
                    readDlnaSessionResult(token, current::get) {
                        withContext(Dispatchers.IO) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            90_000L
                        }
                    }?.onSuccess { published.set(true) }
                }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                operation.cancel()
                current.set(current.get().connectingTo(CastDevice("b", "B"), 0L))
            } finally {
                release.countDown()
                operation.join()
            }
            assertFalse(published.get())
            assertEquals("b", current.get().activeDeviceId)
        }

    @Test
    fun late_failure_from_previous_load_on_same_receiver_is_discarded() =
        runBlocking {
            val current = AtomicReference(playing("a"))
            val token = current.get().castSessionToken()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val operation =
                async(Dispatchers.Default) {
                    readDlnaSessionResult(token, current::get) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw IOException("HTTP 401 from old load")
                    }
                }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                current.set(current.get().connectingTo(CastDevice("a", "A"), 15_000L))
            } finally {
                release.countDown()
            }
            assertNull(operation.await())
            assertEquals(15_000L, current.get().positionMs)
        }

    @Test
    fun explicit_stop_rejects_a_late_result_without_needing_a_new_revision() =
        runTest {
            var current = playing("a")
            val token = current.castSessionToken()
            val result =
                readDlnaSessionResult(token, { current }) {
                    current = current.userStopped()
                    42_000L
                }
            assertNull(result)
            assertEquals(CastTermination.UserStop, current.termination)
        }

    @Test
    fun failed_load_rollback_and_retry_never_reuse_previous_or_failed_tokens() =
        runTest {
            var current = playing("a").remoteUpdate(CastPlaybackStatus.Playing, positionMs = 12_000L)
            val originalToken = current.castSessionToken()
            val previous = current
            val failed = current.connectingTo(CastDevice("a", "A"), 30_000L)
            val failedToken = failed.castSessionToken()
            current = failed
            val lateResult =
                readDlnaSessionResult(failedToken, { current }) {
                    current = restoreCastSessionAfterFailedLoad(previous, current, "load failed")
                    current = current.connectingTo(CastDevice("a", "A"), 50_000L)
                    "old load accepted"
                }
            assertNull(lateResult)
            assertFalse(originalToken.matches(current))
            assertFalse(failedToken.matches(current))
            assertEquals(4L, current.sessionRevision)
            assertEquals(50_000L, current.positionMs)
        }

    @Test
    fun rollback_preserves_previous_playback_but_rejects_both_old_custom_receipts() {
        val previous =
            playing("a")
                .remoteUpdate(CastPlaybackStatus.Playing, positionMs = 12_000L)
                .withReceiverCapabilities(
                    revision = 1L,
                    dolbyVision = CastCapability.Supported,
                    dolbyAtmos = CastCapability.Supported,
                    requestedMedia = CastCapability.Supported,
                ).withReceiverOutputReceipt(1L, true, true, true, "previous media")
        val failed = previous.connectingTo(CastDevice("b", "B"), 0L)
        val restored = restoreCastSessionAfterFailedLoad(previous, failed, "load failed")
        assertEquals(3L, restored.sessionRevision)
        assertEquals("a", restored.activeDeviceId)
        assertEquals(12_000L, restored.positionMs)
        assertTrue(restored.hasActiveSession)
        assertFalse(restored.capabilities.receiverConfirmed)
        assertFalse(restored.outputEvidence.playbackConfirmed)
        assertEquals(3L, restored.outputEvidence.sessionRevision)
        for (staleRevision in listOf(previous.sessionRevision, failed.sessionRevision)) {
            assertEquals(
                restored,
                restored.withReceiverCapabilities(
                    staleRevision,
                    CastCapability.Supported,
                    CastCapability.Supported,
                    CastCapability.Supported,
                ),
            )
            assertEquals(restored, restored.withReceiverOutputReceipt(staleRevision, true, true, true, "late"))
        }
    }

    @Test
    fun failure_without_previous_playback_keeps_attempted_target_and_advances_generation() {
        val previous = CastState()
        val failed = previous.connectingTo(CastDevice("b", "B"), 9_000L)
        val restored = restoreCastSessionAfterFailedLoad(previous, failed, "receiver rejected load")
        assertEquals(2L, restored.sessionRevision)
        assertEquals("b", restored.activeDeviceId)
        assertEquals(9_000L, restored.positionMs)
        assertEquals(CastPlaybackStatus.Error, restored.status)
        assertFalse(restored.hasActiveSession)
        assertEquals(3L, restored.connectingTo(CastDevice("b", "B"), 0L).sessionRevision)
    }

    private fun playing(id: String): CastState =
        CastState().connectingTo(CastDevice(id, id), 0L).remoteUpdate(CastPlaybackStatus.Playing)
}
