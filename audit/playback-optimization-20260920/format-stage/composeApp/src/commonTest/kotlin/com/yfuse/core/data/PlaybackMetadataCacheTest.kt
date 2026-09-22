package com.yfuse.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackMetadataCacheTest {
    @Test
    fun concurrent_launch_reuses_detail_request_and_refresh_replaces_it() =
        runTest {
            val cache = PlaybackMetadataCache<String, Int>()
            val ready = CompletableDeferred<Unit>()
            var requests = 0
            val detail =
                async {
                    cache.get("source", reuse = false) {
                        requests++
                        ready.await()
                        1
                    }
                }
            val player =
                async {
                    cache.get("source") {
                        requests++
                        2
                    }
                }
            runCurrent()
            assertEquals(1, requests)
            ready.complete(Unit)
            assertEquals(1, detail.await())
            assertEquals(1, player.await())
            assertEquals(3, cache.get("source", reuse = false) { 3 })
            assertEquals(3, cache.get("source") { error("Should reuse refreshed metadata") })
        }

    @Test
    fun credentials_expiration_and_failure_do_not_reuse_stale_metadata() =
        runTest {
            var now = 0L
            val cache = PlaybackMetadataCache<Pair<String, String>, Int>(ttlMs = 15L, nowMs = { now })
            assertEquals(1, cache.get("item" to "token-a") { 1 })
            assertEquals(2, cache.get("item" to "token-b") { 2 })
            now = 15
            assertEquals(3, cache.get("item" to "token-a") { 3 })
            assertFailsWith<IllegalStateException> { cache.get("broken" to "token-a") { error("network") } }
            assertEquals(4, cache.get("broken" to "token-a") { 4 })
        }

    @Test
    fun cancelled_detail_owner_does_not_cancel_waiting_player() =
        runTest {
            val cache = PlaybackMetadataCache<String, Int>()
            val owner = async { cache.get("source") { awaitCancellation() } }
            runCurrent()
            val player = async { cache.get("source") { 42 } }
            runCurrent()
            owner.cancelAndJoin()
            assertEquals(42, player.await())
        }

    @Test
    fun bounded_entries_and_invalidation_do_not_resurrect_old_inflight_values() =
        runTest {
            val cache = PlaybackMetadataCache<String, Int>(capacity = 2)
            val ready = CompletableDeferred<Unit>()
            val old =
                async {
                    cache.get("a") {
                        ready.await()
                        1
                    }
                }
            runCurrent()
            cache.invalidate { it == "a" }
            assertEquals(2, cache.get("a") { 2 })
            ready.complete(Unit)
            assertEquals(1, old.await())
            assertEquals(2, cache.get("a") { error("stale completion") })
            cache.get("b") { 3 }
            cache.get("c") { 4 }
            assertEquals(5, cache.get("a") { 5 })
        }
}
