package com.yfuse.core.handoff

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffPlaybackRegistryTest {
    private val media = HandoffMedia("tmdb:603", "Movie", "server", "item", 1_000, 60_000, mediaSourceId = "4k")

    @Test
    fun readinessRequiresMatchingVersionAndActualPlaying() =
        runTest {
            val registry = HandoffPlaybackRegistry()
            val source =
                object : HandoffPlaybackRegistry.Source {
                    override fun snapshot() = media

                    override suspend fun pauseAndSnapshot() = media

                    override suspend fun resume() {}
                }
            registry.source = source
            val result = async { registry.awaitPlayback(media) }
            registry.publish(source, ActiveHandoffPlayback(media, ready = true, playing = false))
            runCurrent()
            assertFalse(result.isCompleted)
            registry.publish(source, ActiveHandoffPlayback(media.copy(mediaSourceId = "1080p"), true, true))
            runCurrent()
            assertFalse(result.isCompleted)
            registry.publish(source, ActiveHandoffPlayback(media, true, true))
            runCurrent()
            assertEquals(media, assertIs<HandoffStartResult.Playing>(result.await()).last.media)
        }

    @Test
    fun aSlowStartupThatKeepsMovingIsNotCutOff() =
        runTest {
            val registry = HandoffPlaybackRegistry()
            val source = source(registry)
            val result = async { registry.awaitPlayback(media) }
            // The evening that prompted this: data trickling in for well past the old 15 s limit.
            repeat(8) { tick ->
                advanceTimeBy(5_000)
                registry.publish(source, ActiveHandoffPlayback(media, false, false, progress = tick.toLong()))
                runCurrent()
                assertFalse(result.isCompleted, "cut off after ${(tick + 1) * 5} s")
            }
            registry.publish(source, ActiveHandoffPlayback(media, true, true, progress = 99))
            runCurrent()
            assertIs<HandoffStartResult.Playing>(result.await())
        }

    @Test
    fun aStartupWithNoProgressStallsAndReportsWhatItLastSaw() =
        runTest {
            val registry = HandoffPlaybackRegistry()
            val source = source(registry)
            val slow =
                ActiveHandoffPlayback(
                    media,
                    ready = false,
                    playing = false,
                    progress = 1,
                    networkBitsPerSecond = 2_000_000,
                    sourceBitsPerSecond = 13_000_000,
                )
            val result = async { registry.awaitPlayback(media) }
            registry.publish(source, slow)
            runCurrent()
            // Republishing the same state is not progress.
            advanceTimeBy(15_000)
            registry.publish(source, slow.copy())
            runCurrent()
            assertFalse(result.isCompleted)
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(slow, assertIs<HandoffStartResult.Stalled>(result.await()).last)
        }

    @Test
    fun aFailedPlayerEndsTheWaitAtOnce() =
        runTest {
            val registry = HandoffPlaybackRegistry()
            val source = source(registry)
            val result = async { registry.awaitPlayback(media) }
            registry.publish(source, ActiveHandoffPlayback(media, false, false, failed = true))
            runCurrent()
            assertIs<HandoffStartResult.Failed>(result.await())
        }

    @Test
    fun theReceiverExplainsItsOwnFailure() =
        runTest {
            val registry = HandoffPlaybackRegistry()
            registry.receiver =
                object : HandoffPlaybackRegistry.Receiver {
                    override suspend fun prepare(media: HandoffMedia) = false

                    override suspend fun start(media: HandoffMedia) = false

                    override suspend fun release() {}

                    override fun failureReason() = "本机正在播放其他影片"
                }
            assertNull(registry.receiveFailureReason())
            assertFalse(registry.prepare(media))
            assertEquals("本机正在播放其他影片", registry.receiveFailureReason())
            registry.releasePrepared()
            assertNull(registry.receiveFailureReason())
        }

    private fun source(registry: HandoffPlaybackRegistry) =
        object : HandoffPlaybackRegistry.Source {
            override fun snapshot() = media

            override suspend fun pauseAndSnapshot() = media

            override suspend fun resume() {}
        }.also { registry.source = it }

    @Test
    fun failureReleasesButSuccessOnlyDropsPreparedHandle() =
        runTest {
            val registry = HandoffPlaybackRegistry()
            var released = 0
            var completed = 0
            registry.receiver =
                object : HandoffPlaybackRegistry.Receiver {
                    override suspend fun prepare(media: HandoffMedia) = true

                    override suspend fun start(media: HandoffMedia) = true

                    override suspend fun release() {
                        released++
                    }

                    override fun transferCompleted() {
                        completed++
                    }
                }
            registry.prepare(media)
            registry.releasePrepared()
            registry.finishTransfer()
            assertEquals(1, released)
            assertEquals(0, completed)
            registry.prepare(media)
            registry.startPrepared(media)
            registry.finishTransfer()
            assertEquals(1, released)
            assertEquals(1, completed)
        }

    @Test
    fun staleConsumerCannotClearAnotherTransfersPreferences() {
        val registry = HandoffPlaybackRegistry()
        val newer = media.copy(positionMs = 5_000)
        registry.offerPreferences(media)
        registry.offerPreferences(newer)
        registry.clearPreferences(media)
        assertEquals(newer, registry.pendingPreferences.value)
        registry.clearPreferences(newer)
        assertNull(registry.pendingPreferences.value)
        assertTrue(media.valid())
        assertFalse(media.copy(subtitleOffsetMs = Long.MAX_VALUE).valid())
    }
}
