package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
