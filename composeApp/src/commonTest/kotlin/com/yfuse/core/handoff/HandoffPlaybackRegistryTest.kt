package com.yfuse.core.handoff

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            val result = async { registry.awaitPlaying(media) }
            registry.publish(source, ActiveHandoffPlayback(media, ready = true, playing = false))
            runCurrent()
            assertFalse(result.isCompleted)
            registry.publish(source, ActiveHandoffPlayback(media.copy(mediaSourceId = "1080p"), true, true))
            runCurrent()
            assertFalse(result.isCompleted)
            registry.publish(source, ActiveHandoffPlayback(media, true, true))
            runCurrent()
            assertEquals(media, result.await().media)
        }

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
