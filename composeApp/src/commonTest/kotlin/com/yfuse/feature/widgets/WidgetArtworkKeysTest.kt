package com.yfuse.feature.widgets

import coil3.memory.MemoryCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetArtworkKeysTest {
    @Test
    fun returning_uses_last_decoded_size_without_sharing_across_urls_or_accounts() {
        val keys = WidgetArtworkKeys()
        val first = MemoryCache.Key("poster", mapOf("size" to "200x300"))
        val resized = MemoryCache.Key("poster", mapOf("size" to "400x600"))
        val url = "https://example.test/poster?token=alice"
        keys.resolved(url, first)
        assertEquals(first, keys[url])
        assertNull(keys["https://example.test/poster?token=bob"])
        keys.resolved(url, resized)
        assertEquals(resized, keys[url])
    }

    @Test
    fun recycled_cards_keep_recent_keys_with_a_bounded_lru() {
        val keys = WidgetArtworkKeys(2)
        val key = MemoryCache.Key("image")
        keys.resolved("one", key)
        keys.resolved("two", key)
        assertEquals(key, keys["one"])
        keys.resolved("three", key)
        assertNull(keys["two"])
        assertEquals(key, keys["one"])
        assertEquals(key, keys["three"])
    }
}
