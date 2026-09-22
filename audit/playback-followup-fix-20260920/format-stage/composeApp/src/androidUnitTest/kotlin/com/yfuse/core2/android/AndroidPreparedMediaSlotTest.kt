package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidPreparedMediaSlotTest {
    @Test
    fun changed_track_intent_rejects_a_prepared_probe_without_changing_the_byte_cache_identity() {
        val original =
            YMediaItem(
                "movie",
                "https://media/movie",
                cacheIdentity =
                    com.yfuse.core2.network
                        .YCacheIdentity("scope", "movie", "file"),
            )
        val changed =
            original.copy(
                initialTrackSelection =
                    com.yfuse.core2.api.YInitialTrackSelection(
                        audio =
                            com.yfuse.core2.api
                                .YTrackPreference(language = "zho"),
                    ),
            )
        val slot = AndroidPreparedMediaSlot<Any> {}
        val source = Any()
        slot.offer(original, source)
        assertEquals(original.cacheIdentity, changed.cacheIdentity)
        assertNull(slot.take(changed))
        assertSame(
            source,
            slot.take(
                original.copy(
                    initialTrackSelection =
                        com.yfuse.core2.api
                            .YInitialTrackSelection(),
                ),
            ),
        )
        assertTrue(original.verifiedRouteIdentity() != changed.verifiedRouteIdentity())
        slot.close()
    }

    private val item = YMediaItem("movie", "https://media/movie", headers = mapOf("Authorization" to "original"))

    @Test fun unclaimed_sources_expire_but_transferred_sources_survive_the_deadline() {
        val expired = CountDownLatch(1)
        val slot = AndroidPreparedMediaSlot<Any>(expiryMillis = 20L) { expired.countDown() }
        slot.offer(item, Any())
        assertTrue(expired.await(2, TimeUnit.SECONDS))
        assertNull(slot.take(item))
        val taken = Any()
        val incorrectlyReleased = CountDownLatch(1)
        val transferred = AndroidPreparedMediaSlot<Any>(expiryMillis = 100L) { incorrectlyReleased.countDown() }
        transferred.offer(item, taken)
        assertSame(taken, transferred.take(item))
        assertFalse(incorrectlyReleased.await(200L, TimeUnit.MILLISECONDS))
        transferred.close()
    }

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
