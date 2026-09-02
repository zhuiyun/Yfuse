package com.yfuse.core2.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YPlayerQueueTest {
    @Test
    fun `tail extension preserves order and metadata`() {
        val first = YMediaItem(id = "e1", uri = "https://media/e1")
        val second = YMediaItem(id = "e2", uri = "https://media/e2", title = "Episode 2")

        assertEquals(listOf(first, second), listOf(first).appendingDistinct(listOf(second)))
    }

    @Test
    fun `duplicate identity rejects the entire extension`() {
        val first = YMediaItem(id = "e1", uri = "https://media/e1")

        assertNull(listOf(first).appendingDistinct(listOf(first.copy(uri = "https://other/e1"))))
        assertNull(
            listOf(first).appendingDistinct(
                listOf(
                    YMediaItem(id = "e2", uri = "https://media/e2"),
                    YMediaItem(id = "e2", uri = "https://other/e2"),
                ),
            ),
        )
    }

    @Test
    fun `empty extension keeps the existing queue instance`() {
        val queue = listOf(YMediaItem(id = "e1", uri = "https://media/e1"))

        assertSame(queue, queue.appendingDistinct(emptyList()))
    }

    @Test
    fun `materially early eos is rejected but a normal duration tail is accepted`() {
        assertTrue(isPrematurePlaybackEnd(positionMs = 600_000L, durationMs = 2_400_000L))
        assertFalse(isPrematurePlaybackEnd(positionMs = 2_360_000L, durationMs = 2_400_000L))
        assertFalse(isPrematurePlaybackEnd(positionMs = 20_000L, durationMs = 30_000L))
        assertFalse(isPrematurePlaybackEnd(positionMs = 10_000L, durationMs = 0L))
    }
}
