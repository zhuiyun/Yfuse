package com.yfuse.core2.android

import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YTrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDemuxInputQueueTest {
    @Test
    fun blockedVideoDoesNotPreventAudioAndRetainsItsSampleUntilAccepted() {
        val input = AndroidDemuxInputQueue()
        val video = sample(0)
        val audio = sample(1)
        input.retain(video)
        var polled = false
        val accepted = mutableListOf<YQueuedDemuxResult.Sample>()
        val result =
            input.pump(
                poll = { blocked ->
                    assertTrue(YTrackId(0) in blocked)
                    if (polled) YQueuedDemuxResult.EndOfInput else audio.also { polled = true }
                },
                queue = { next -> (next === audio).also { if (it) accepted += next } },
            )
        assertTrue(result.didWork)
        assertFalse(result.ended)
        assertEquals(listOf(audio), accepted)
        assertEquals(1, input.pendingCount)
        val drained =
            input.pump({ YQueuedDemuxResult.EndOfInput }) {
                accepted += it
                true
            }
        assertTrue(drained.ended)
        assertEquals(listOf(audio, video), accepted)
        assertEquals(0, input.pendingCount)
    }

    @Test
    fun seekDuringAdmissionDoesNotRetainOrQueueOldGeneration() {
        val input = AndroidDemuxInputQueue()
        input.retain(sample(0))
        input.retain(sample(1))
        var calls = 0
        val result =
            input.pump(poll = { error("stale pump must stop") }) {
                calls++
                input.clear()
                input.advanceGeneration()
                false
            }
        assertTrue(result.didWork)
        assertEquals(1, calls)
        assertEquals(0, input.pendingCount)
        assertTrue(input.pump({ YQueuedDemuxResult.Empty }, { true }).starved)
    }

    private fun sample(track: Int) = YQueuedDemuxResult.Sample(YCompressedSample(YTrackId(track), byteArrayOf(1), 0))
}
