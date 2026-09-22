package com.yfuse.core.designsystem

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArtworkColorCacheTest {
    private fun key(url: String) = ArtworkColorKey(url, ArtworkColorSample.Dominant)

    @Test
    fun equal_requests_share_work_and_one_hidden_subscriber_does_not_cancel_the_other() =
        runTest {
            val cache = ArtworkColorCache(backgroundScope)
            val ready = CompletableDeferred<Unit>()
            var calls = 0
            var cancelled = false
            val extract: suspend () -> Int? = {
                calls++
                try {
                    ready.await()
                    42
                } finally {
                    cancelled = !ready.isCompleted
                }
            }
            val first = async { cache.get(key("a"), extract) }
            val second = async { cache.get(key("a"), extract) }
            runCurrent()
            first.cancelAndJoin()
            assertEquals(1, calls)
            assertFalse(cancelled)
            ready.complete(Unit)
            assertEquals(42, second.await())
            assertEquals(42, cache.get(key("a")) { error("Use the derived result") })
        }

    @Test
    fun last_subscriber_cancellation_stops_extraction_and_returning_can_retry() =
        runTest {
            val cache = ArtworkColorCache(backgroundScope)
            var stopped = false
            val request =
                async {
                    cache.get(key("a")) {
                        try {
                            awaitCancellation()
                        } finally {
                            stopped = true
                        }
                    }
                }
            runCurrent()
            request.cancelAndJoin()
            runCurrent()
            assertTrue(stopped)
            assertEquals(7, cache.get(key("a")) { 7 })
        }

    @Test
    fun lru_is_bounded_and_access_refreshes_recency() =
        runTest {
            val cache = ArtworkColorCache(backgroundScope, maxEntries = 2)
            assertEquals(1, cache.get(key("a")) { 1 })
            assertEquals(2, cache.get(key("b")) { 2 })
            assertEquals(1, cache.get(key("a")) { error("a remains cached") })
            assertEquals(3, cache.get(key("c")) { 3 })
            assertEquals(1, cache.get(key("a")) { error("a was recently used") })
            assertEquals(22, cache.get(key("b")) { 22 })
        }

    @Test
    fun no_more_than_two_decodes_run_and_cancelled_queued_requests_never_start() =
        runTest {
            val cache = ArtworkColorCache(backgroundScope, maxConcurrent = 2)
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()

            fun request(id: String) =
                async {
                    cache.get(key(id)) {
                        started += id
                        release.await()
                        1
                    }
                }
            val first = request("a")
            val second = request("b")
            val queued = request("c")
            runCurrent()
            assertEquals(listOf("a", "b"), started)
            queued.cancelAndJoin()
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(listOf("a", "b"), started)
        }

    @Test
    fun page_keys_preserve_crop_fade_and_algorithm_identity_with_invalid_inputs_normalized() {
        val page = artworkPageColorKey("a", 16f / 9f, 0.25f)
        assertNotEquals(page, page.copy(aspectRatio = 1f))
        assertNotEquals(page, page.copy(fadeFraction = 0.5f))
        assertNotEquals(page, page.copy(algorithmVersion = 3))
        assertNotEquals(page, key("a"))
        assertEquals(artworkPageColorKey("a", 0f, 0.25f), artworkPageColorKey("a", Float.NaN, Float.NaN))
    }

    @Test
    fun unavailable_colours_are_not_cached_as_success() =
        runTest {
            val cache = ArtworkColorCache(backgroundScope)
            assertEquals(null, cache.get(key("a")) { null })
            assertEquals(8, cache.get(key("a")) { 8 })
        }
}
