package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidPreparedMediaSlotTest {
    private val item = YMediaItem("movie", "https://media/movie", headers = mapOf("Authorization" to "original"))

    @Test
    fun exact_source_is_transferred_once_and_no_longer_closed_by_the_slot() {
        val released = mutableListOf<Any>()
        val slot = AndroidPreparedMediaSlot<Any> { released += it }
        val resource = Any()
        slot.offer(item, resource)
        assertNull(slot.take(item.copy(headers = mapOf("Authorization" to "refreshed"))))
        assertNull(slot.take(item.copy(uri = "https://media/refreshed")))
        assertSame(resource, slot.take(item))
        assertNull(slot.take(item))
        slot.close()
        assertEquals(emptyList(), released)
    }

    @Test
    fun replacing_or_abandoning_a_probe_releases_each_owned_resource_once() {
        val released = mutableListOf<Any>()
        val slot = AndroidPreparedMediaSlot<Any> { released += it }
        val first = Any()
        val second = Any()
        slot.offer(item, first)
        slot.offer(item, second)
        slot.close()
        slot.close()
        assertEquals(listOf(first, second), released)
    }
}
