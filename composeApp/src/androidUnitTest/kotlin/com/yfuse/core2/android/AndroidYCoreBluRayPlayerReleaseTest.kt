package com.yfuse.core2.android

import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidYCoreBluRayPlayerReleaseTest {
    @Test
    fun `registry removal does not release the wrapper barrier before its decoder`() =
        runTest {
            val child = ControlledChild()
            val unregistered = mutableListOf<Long>()
            val player = player(child) { unregistered += it }
            val barrier = AndroidPlayerReleaseBarrier()

            player.release()
            barrier.retire(player)
            val waiting = async { barrier.await() }
            runCurrent()

            assertEquals(listOf(17L), unregistered)
            assertFalse(player.releaseCompleted)
            assertFalse(barrier.idle)
            assertFalse(waiting.isCompleted)

            child.finishRelease()
            waiting.await()
            player.releaseAndJoin()
            assertTrue(player.releaseCompleted)
            assertTrue(barrier.idle)
            assertEquals(listOf(17L), unregistered)
        }

    @Test
    fun `a failed delegate wait remains pending and a later wait can finish`() =
        runTest {
            val child = ControlledChild().apply { failWait = true }
            val player = player(child)

            assertFailsWith<IllegalStateException> { player.releaseAndJoin() }
            assertFalse(player.releaseCompleted)

            child.failWait = false
            child.finishRelease()
            player.releaseAndJoin()
            assertTrue(player.releaseCompleted)
        }

    @Test
    fun `failed source unregister never reports completion even after decoder cleanup`() =
        runTest {
            val child = ControlledChild().apply { finishRelease() }
            var attempts = 0
            val player =
                player(child) {
                    attempts++
                    check(attempts > 1) { "simulated unregister failure" }
                }

            assertFailsWith<IllegalStateException> { player.releaseAndJoin() }
            assertFalse(player.releaseCompleted)

            player.releaseAndJoin()
            assertTrue(player.releaseCompleted)
            assertEquals(2, attempts)
        }

    private fun player(
        child: ControlledChild,
        unregister: (Long) -> Unit = {},
    ) = AndroidYCoreBluRayPlayer(
        delegate = child,
        navigation = MutableStateFlow(PlaybackDiscNavigationState()),
        nativeId = 17L,
        unregisterSource = unregister,
    )

    private class ControlledChild :
        YPlayer,
        AndroidSerializedPlayerRelease {
        override val state = MutableStateFlow(YPlayerState())
        private val released = CompletableDeferred<Unit>()
        var failWait = false
        override val releaseCompleted: Boolean get() = released.isCompleted

        fun finishRelease() {
            released.complete(Unit)
        }

        override fun release() = Unit

        override suspend fun releaseAndJoin() {
            check(!failWait) { "simulated decoder timeout" }
            released.await()
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
    }
}
