package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNextItemPreloadWindowTest {
    @Test
    fun `insufficient buffer never becomes permission merely because two minutes passed`() =
        runTest {
            var state = HEALTHY.copy(buffering = true, bufferedPositionMs = 1_000L)
            val ready = async { awaitCore2NextItemPreloadWindow { state } }
            advanceTimeBy(180_000L)
            runCurrent()
            assertFalse(ready.isCompleted)

            state = HEALTHY
            advanceTimeBy(6_000L)
            runCurrent()
            assertTrue(ready.await())
        }

    @Test
    fun `a brief refill cannot trigger next-item traffic`() =
        runTest {
            var state = HEALTHY
            val ready = async { awaitCore2NextItemPreloadWindow { state } }
            advanceTimeBy(19_000L)
            runCurrent()
            assertFalse(ready.isCompleted)

            state = HEALTHY.copy(buffering = true)
            advanceTimeBy(1_000L)
            runCurrent()
            state = HEALTHY
            advanceTimeBy(5_000L)
            runCurrent()
            assertFalse(ready.isCompleted)
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(ready.await())
        }

    @Test
    fun `paused playback and faster consumption both defer preloading`() =
        runTest {
            var state = HEALTHY.copy(playing = false)
            val ready = async { awaitCore2NextItemPreloadWindow { state } }
            advanceTimeBy(30_000L)
            runCurrent()
            assertFalse(ready.isCompleted)

            // Fifteen media seconds at 2x provide only 7.5 seconds of network headroom.
            state = HEALTHY.copy(speed = 2f)
            advanceTimeBy(30_000L)
            runCurrent()
            assertFalse(ready.isCompleted)
            state = HEALTHY.copy(speed = 2f, bufferedPositionMs = 30_000L)
            advanceTimeBy(6_000L)
            runCurrent()
            assertTrue(ready.await())
        }

    @Test
    fun `a replaced child stops waiting instead of starting its old preload`() =
        runTest {
            var state: YPlayerState? = HEALTHY.copy(buffering = true)
            val ready = async { awaitCore2NextItemPreloadWindow { state } }
            advanceTimeBy(16_000L)
            runCurrent()
            state = null
            advanceTimeBy(1_000L)
            runCurrent()
            assertFalse(ready.await())
        }

    @Test
    fun `ended or failed playback does not warm another source`() =
        runTest {
            for (phase in listOf(YPlaybackPhase.Ended, YPlaybackPhase.Failed)) {
                assertFalse(awaitCore2NextItemPreloadWindow { HEALTHY.copy(phase = phase) })
            }
        }

    @Test
    fun `cancelling the waiting job stops the preload window`() =
        runTest {
            val ready = async { awaitCore2NextItemPreloadWindow { HEALTHY.copy(buffering = true) } }
            advanceTimeBy(16_000L)
            ready.cancel()
            ready.join()
            assertTrue(ready.isCancelled)
        }

    private companion object {
        val HEALTHY =
            YPlayerState(
                phase = YPlaybackPhase.Ready,
                playing = true,
                playbackRequested = true,
                bufferedPositionMs = 15_000L,
            )
    }
}
