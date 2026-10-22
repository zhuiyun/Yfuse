package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVideoOutputDetachTest {
    @Test
    fun the_wait_ends_when_the_worker_confirms_the_detach() {
        val player = DetachingPlayer { detached -> thread { detached.complete(Unit) } }

        assertTrue(detachVideoOutputAwaiting(player, timeoutMs = 5_000L))
        assertEquals(1, player.detachRequests)
    }

    @Test
    fun a_busy_worker_cannot_hold_the_caller_past_the_bound() {
        val player = DetachingPlayer { /* never confirms */ }

        assertFalse(detachVideoOutputAwaiting(player, timeoutMs = 20L))
        assertEquals(1, player.detachRequests)
    }

    @Test
    fun a_player_without_a_worker_is_detached_directly() {
        val player = DetachingPlayer(confirm = null)
        val plain = object : YPlayer by player {}

        assertTrue(detachVideoOutputAwaiting(plain, timeoutMs = 20L))
        assertEquals(listOf<YVideoOutput?>(null), player.outputs)
    }

    @Test
    fun release_request_does_not_ack_until_worker_finally_and_child_cleanup_finish() =
        runTest {
            val workerCleanup = CompletableDeferred<Unit>()
            val childCleanup = CompletableDeferred<Unit>()
            var childReleased = false
            val worker =
                backgroundScope.launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { workerCleanup.await() }
                    }
                }
            runCurrent()
            val player =
                object : AndroidSerializedPlayerRelease {
                    override val releaseCompleted get() = worker.isCompleted && childReleased

                    override suspend fun releaseAndJoin() {
                        worker.join()
                        childCleanup.await()
                        childReleased = true
                    }
                }
            // release() has already set its flag and cancelled the worker, but finally still renders.
            worker.cancel()
            runCurrent()
            val detached = CompletableDeferred<Unit>()
            confirmVideoDetachAfterWorkerRelease(worker, player, detached, backgroundScope)
            assertFalse(detached.isCompleted)

            workerCleanup.complete(Unit)
            runCurrent()
            assertTrue(worker.isCompleted)
            assertFalse(detached.isCompleted) // The adaptive router's retiring child still owns output.

            childCleanup.complete(Unit)
            runCurrent()
            assertTrue(player.releaseCompleted)
            assertTrue(detached.isCompleted)
        }

    @Test
    fun a_failed_cleanup_cannot_be_reported_as_a_confirmed_detach() =
        runTest {
            val player =
                object : AndroidSerializedPlayerRelease {
                    override val releaseCompleted = false

                    override suspend fun releaseAndJoin() {
                        error("decoder cleanup failed")
                    }
                }
            val worker = backgroundScope.launch { }
            runCurrent()
            val detached = CompletableDeferred<Unit>()
            confirmVideoDetachAfterWorkerRelease(worker, player, detached, backgroundScope)
            runCurrent()
            assertFalse(detached.isCompleted)
        }

    @Test
    fun cancellation_of_the_confirmation_is_not_success() {
        val player = DetachingPlayer { it.cancel() }
        assertFalse(detachVideoOutputAwaiting(player, timeoutMs = 20L))
    }

    private class DetachingPlayer(
        private val confirm: ((CompletableDeferred<Unit>) -> Unit)?,
    ) : YPlayer,
        AndroidVideoOutputDetach {
        override val state: StateFlow<YPlayerState> = MutableStateFlow(YPlayerState())
        val outputs = mutableListOf<YVideoOutput?>()
        var detachRequests = 0

        override fun detachVideoOutput(detached: CompletableDeferred<Unit>) {
            detachRequests++
            checkNotNull(confirm).invoke(detached)
        }

        override fun setVideoOutput(output: YVideoOutput?): Boolean {
            outputs += output
            return true
        }

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun selectTrack(
            type: YTrackType,
            id: String,
        ) = Unit

        override fun selectItem(index: Int) = Unit

        override fun retry() = Unit

        override fun release() = Unit
    }
}
