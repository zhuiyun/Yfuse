package com.yfuse.core2.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun `source key ignores header order but not the address or a header value`() {
        val headers = linkedMapOf("User-Agent" to "player", "X-Emby-Token" to "a")
        val reordered = linkedMapOf("X-Emby-Token" to "a", "User-Agent" to "player")
        val key = yMediaSourceKey("https://media/e1.mkv?api_key=secret", headers)

        assertEquals(key, yMediaSourceKey("https://media/e1.mkv?api_key=secret", reordered))
        assertEquals(16, key.length)
        assertFalse("secret" in key)
        assertTrue(key != yMediaSourceKey("https://media/e2.mkv?api_key=secret", headers))
        assertTrue(key != yMediaSourceKey("https://media/e1.mkv?api_key=secret", headers + ("X-Emby-Token" to "b")))
    }

    @Test
    fun `a loopback route minted per open still matches the same upstream entry`() {
        val key = yMediaSourceKey("https://media/master.m3u8", mapOf("User-Agent" to "player"))
        val opened =
            YMediaItem(
                id = "e1",
                uri = "http://127.0.0.1:41234/route/7f0e",
                headers = mapOf("User-Agent" to "player"),
                sourceKey = key,
            )
        val refreshed =
            YMediaItem(
                id = "e1",
                uri = "https://media/master.m3u8",
                title = "Episode 1",
                headers = mapOf("User-Agent" to "player", "X-Emby-Token" to "a"),
                sourceKey = key,
            )

        assertTrue(opened.hasSameActiveSourceAs(refreshed))
        assertFalse(opened.hasSameActiveSourceAs(refreshed.copy(sourceKey = "0000000000000000")))
        assertFalse(opened.hasSameActiveSourceAs(refreshed.copy(id = "e2")))
    }

    @Test
    fun `entries without a source key compare by exact address and headers`() {
        val opened = YMediaItem(id = "e1", uri = "https://media/e1", headers = mapOf("A" to "1"))

        assertTrue(opened.hasSameActiveSourceAs(opened.copy(title = "Episode 1")))
        assertFalse(opened.hasSameActiveSourceAs(opened.copy(uri = "https://media/other")))
        assertFalse(opened.hasSameActiveSourceAs(opened.copy(headers = mapOf("A" to "2"))))
    }

    @Test
    fun `a refreshed queue keeps open addresses and takes metadata from the refresh`() {
        val key = yMediaSourceKey("https://media/e1.m3u8", emptyMap())
        val active =
            listOf(YMediaItem(id = "e1", uri = "http://127.0.0.1:41234/route/1", sourceKey = key))
        val refreshed =
            listOf(
                YMediaItem(id = "e1", uri = "https://media/e1.m3u8", title = "Episode 1", sourceKey = key),
                YMediaItem(id = "e2", uri = "https://media/e2.mkv", title = "Episode 2"),
            )

        val merged = refreshed.retainingActiveSources(active)

        assertEquals("http://127.0.0.1:41234/route/1", merged[0].uri)
        assertEquals("Episode 1", merged[0].title)
        assertEquals(refreshed[1], merged[1])
    }

    @Test
    fun `materially early eos is rejected but a normal duration tail is accepted`() {
        assertTrue(isPrematurePlaybackEnd(positionMs = 600_000L, durationMs = 2_400_000L))
        assertFalse(isPrematurePlaybackEnd(positionMs = 2_360_000L, durationMs = 2_400_000L))
        assertFalse(isPrematurePlaybackEnd(positionMs = 20_000L, durationMs = 30_000L))
        assertFalse(isPrematurePlaybackEnd(positionMs = 10_000L, durationMs = 0L))
    }
}
