package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidQueueCursorTest {
    @Test
    fun inserting_history_and_reordering_the_catalog_keep_the_same_active_media() {
        val current = YMediaItem("e2", "https://media/e2")
        val previous = YMediaItem("e1", "https://media/e1")
        val next = YMediaItem("e3", "https://media/e3")
        var items = listOf(current)
        var index: Int by AndroidQueueCursor(current.id) { items }
        items = listOf(previous, current, next)
        assertEquals(1, index)
        index = 2
        items = listOf(next, previous, current)
        assertEquals(0, index.toInt())
        assertEquals(next.id, items[index].id)
    }
}
