package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YTransportReadHeadsTest {
    private val second = 1_000_000_000L

    @Test
    fun `a single forward run never reports another run`() {
        val heads = YTransportReadHeads()
        assertFalse(heads.record(0L, 0L))
        for (block in 1L..20L) assertTrue(heads.record(block, block * second / 10))

        assertEquals(emptyList(), heads.otherHeads(20L, 2 * second))
        assertNull(heads.primaryBlock(2 * second))
    }

    @Test
    fun `alternating video and audio runs each keep the other visible`() {
        val heads = YTransportReadHeads()
        // Video 270 blocks in, audio near the start: the layout of the file in the diagnostic.
        heads.record(270L, 0L)
        heads.record(3L, 1L)
        heads.record(271L, 2L)
        heads.record(3L, 3L)

        assertEquals(listOf(3L), heads.otherHeads(271L, 4L))
        assertEquals(listOf(271L), heads.otherHeads(3L, 4L))
        // Video moved a block, audio did not: video is the run the disk cache should follow.
        assertEquals(271L, heads.primaryBlock(4L))
    }

    @Test
    fun `the place a seek left is never given a window of its own`() {
        val heads = YTransportReadHeads()
        heads.record(10L, 0L)
        heads.record(11L, 1L)
        assertFalse(heads.record(400L, 2L))
        heads.record(401L, 3L)

        assertEquals(emptyList(), heads.otherHeads(401L, 4L))
        assertNull(heads.primaryBlock(4L))
    }

    @Test
    fun `a run not read for five seconds is forgotten`() {
        val heads = YTransportReadHeads()
        heads.record(270L, 0L)
        heads.record(3L, 1L)
        heads.record(270L, 2L)
        heads.record(3L, 3L)
        assertEquals(listOf(270L), heads.otherHeads(3L, 4L))

        // Only audio is read for a while, as after a seek within the audio run's reach.
        assertTrue(heads.record(3L, 3 * second))
        assertEquals(emptyList(), heads.otherHeads(3L, 6 * second))
        // Coming back after that is a new run, which is what a seek back looks like too.
        assertFalse(heads.record(270L, 6 * second))
    }
}
