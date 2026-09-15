package com.yfuse.core.cast

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
