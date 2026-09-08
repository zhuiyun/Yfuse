package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidPlaybackWorkerStepTest {
    @Test
    fun pump_and_snapshot_failures_keep_their_typed_recovery_edge_and_allow_retry() {
        for ((category, stage) in listOf(
            YPlaybackFailureCategory.Network to YPlaybackFailureStage.Demux,
            YPlaybackFailureCategory.Decoder to YPlaybackFailureStage.VideoDecoderQueue,
            YPlaybackFailureCategory.Renderer to YPlaybackFailureStage.VideoRenderer,
        )) {
            val failure = YPlaybackException(category, stage, "injected")
            var reported: Throwable? = null
            assertNull(playbackWorkerStep({ reported = it }) { throw failure })
            assertSame(failure, reported)
            assertEquals(42, playbackWorkerStep({ error("unexpected failure") }) { 42 })
        }
    }

    @Test
    fun cancellation_does_not_publish_a_playback_failure() {
        assertFailsWith<CancellationException> {
            playbackWorkerStep({ error("cancellation must not trigger recovery") }) { throw CancellationException() }
        }
    }

    @Test
    fun codec_backpressure_reuses_preparation_including_absent_metadata() {
        val cache = AndroidAccessUnitCache<Any, ByteArray?>()
        val sample = Any()
        var calls = 0
        repeat(8) {
            assertNull(
                cache.getOrPrepare(sample) {
                    calls++
                    null
                },
            )
        }
        assertEquals(1, calls)
        cache.queued(sample)
        cache.getOrPrepare(sample) {
            calls++
            null
        }
        assertEquals(2, calls)
        cache.clear()
        cache.getOrPrepare(sample) {
            calls++
            null
        }
        assertEquals(3, calls)
    }
}
