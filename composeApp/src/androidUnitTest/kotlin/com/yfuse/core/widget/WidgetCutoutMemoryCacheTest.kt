package com.yfuse.core.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetCutoutMemoryCacheTest {
    private val artwork = WidgetCutoutArtwork("file:///background", "file:///foreground", 320, 480, .1f, .9f)

    @Test
    fun returning_can_display_validated_metadata_before_async_disk_validation() {
        val cache = WidgetCutoutMemoryCache()
        cache.put("poster", artwork, cache.generation())
        assertEquals(artwork, cache.peek("poster"))
        assertEquals(artwork, cache.peek("poster"))
        assertNull(cache.peek("other-poster"))
    }

    @Test
    fun missing_or_corrupt_disk_result_discards_previous_metadata() {
        val cache = WidgetCutoutMemoryCache()
        val generation = cache.generation()
        cache.put("poster", artwork, generation)
        cache.put("poster", null, generation)
        assertNull(cache.peek("poster"))
    }

    @Test
    fun clearing_rejects_in_flight_results_from_before_the_clear() {
        val cache = WidgetCutoutMemoryCache()
        val before = cache.generation()
        cache.put("poster", artwork, before)
        cache.clear()
        assertNull(cache.peek("poster"))
        cache.put("poster", artwork, before)
        assertNull(cache.peek("poster"))
        cache.put("poster", artwork, cache.generation())
        assertEquals(artwork, cache.peek("poster"))
    }

    @Test
    fun only_recent_metadata_is_retained() {
        val cache = WidgetCutoutMemoryCache(2)
        val generation = cache.generation()
        cache.put("one", artwork, generation)
        cache.put("two", artwork, generation)
        assertEquals(artwork, cache.peek("one"))
        cache.put("three", artwork, generation)
        assertNull(cache.peek("two"))
        assertEquals(artwork, cache.peek("one"))
        assertEquals(artwork, cache.peek("three"))
    }
}
