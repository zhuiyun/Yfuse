package com.yfuse.core.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetFeedTest {
    private fun item(id: String) = WidgetMedia(id, "Film $id")

    @Test fun first_load_failure_waits_for_an_explicit_retry_and_bypasses_the_failure_cooldown() =
        runTest {
            val flags = mutableListOf<Boolean>()
            val feed =
                WidgetFeed(backgroundScope, { 0L }, 3600, false) { _, force ->
                    flags += force
                    if (flags.size == 1) error("offline") else listOf(item("recovered"))
                }
            feed.ensureLoaded()
            runCurrent()
            feed.ensureLoaded()
            runCurrent()
            assertEquals(listOf(false), flags)
            feed.retry()
            runCurrent()
            assertEquals(listOf(false, true), flags)
            assertEquals(
                "recovered",
                feed.state.value.items
                    .single()
                    .id,
            )
            assertFalse(feed.state.value.hasMore)
        }

    @Test fun leaving_the_row_does_not_cancel_its_request_or_clear_loaded_content() =
        runTest {
            val response = CompletableDeferred<List<WidgetMedia>>()
            var calls = 0
            val feed =
                WidgetFeed(backgroundScope, { 0L }, 3600, true) { _, _ ->
                    calls++
                    response.await()
                }
            val observer = backgroundScope.launch { feed.state.collect() }
            feed.ensureLoaded()
            runCurrent()
            observer.cancel()
            feed.ensureLoaded()
            response.complete(listOf(item("one")))
            runCurrent()
            assertEquals(listOf(item("one")), feed.state.value.items)
            feed.ensureLoaded()
            runCurrent()
            assertEquals(1, calls)
            assertFalse(feed.state.value.busy)
        }

    @Test fun refresh_keeps_old_items_on_failure_and_retries_the_same_request() =
        runTest {
            val second = CompletableDeferred<List<WidgetMedia>>()
            val flags = mutableListOf<Boolean>()
            val feed =
                WidgetFeed(backgroundScope, { 0L }, 3600, true) { _, force ->
                    flags += force
                    when (flags.size) {
                        1 -> listOf(item("old"))
                        2 -> second.await()
                        else -> listOf(item("new"))
                    }
                }
            feed.ensureLoaded()
            runCurrent()
            feed.refresh()
            feed.refresh()
            runCurrent()
            assertTrue(feed.state.value.busy)
            assertEquals(listOf(item("old")), feed.state.value.items)
            second.completeExceptionally(IllegalStateException("offline"))
            runCurrent()
            assertEquals("offline", feed.state.value.error)
            assertEquals(listOf(item("old")), feed.state.value.items)
            feed.retry()
            runCurrent()
            assertEquals(listOf(item("new")), feed.state.value.items)
            assertEquals(listOf(false, true, true), flags)
            assertNull(feed.state.value.error)
        }

    @Test fun more_reveals_existing_items_before_paging_and_refresh_does_not_disable_pagination_cache() =
        runTest {
            val requests = mutableListOf<Pair<Int, Boolean>>()
            val feed =
                WidgetFeed(backgroundScope, { 0L }, 3600, true) { page, force ->
                    requests += page to force
                    if (page == 1) listOf(item("one"), item("two")) else listOf(item("two"), item("three"))
                }
            feed.ensureLoaded()
            runCurrent()
            feed.more(1)
            runCurrent()
            assertTrue(feed.state.value.expanded)
            assertEquals(1, requests.size)
            feed.more(1)
            runCurrent()
            assertEquals(
                listOf("one", "two", "three"),
                feed.state.value.items
                    .map { it.id },
            )
            feed.refresh()
            runCurrent()
            feed.more(1)
            runCurrent()
            feed.more(1)
            runCurrent()
            assertEquals(listOf(1 to false, 2 to false, 1 to true, 2 to false, 3 to false), requests)
            assertFalse(feed.state.value.hasMore)
        }

    @Test fun returning_after_expiry_keeps_pages_and_failed_pagination_retries_immediately() =
        runTest {
            var now = 0L
            var calls = 0
            val response = CompletableDeferred<List<WidgetMedia>>()
            val requests = mutableListOf<Int>()
            val feed =
                WidgetFeed(backgroundScope, { now }, 1, true) { page, _ ->
                    requests += page
                    calls++
                    when (calls) {
                        1 -> listOf(item("old"))
                        2 -> response.await()
                        else -> listOf(item("next"))
                    }
                }
            feed.ensureLoaded()
            runCurrent()
            now = 1000L
            feed.ensureLoaded()
            runCurrent()
            assertEquals(listOf(1), requests)
            assertEquals(listOf(item("old")), feed.state.value.items)
            feed.more(12)
            runCurrent()
            response.completeExceptionally(IllegalStateException("offline"))
            runCurrent()
            assertEquals(1, feed.state.value.page)
            feed.ensureLoaded()
            runCurrent()
            assertEquals(listOf(1, 2), requests)
            feed.retry()
            runCurrent()
            assertEquals(listOf(1, 2, 2), requests)
            assertEquals(listOf(item("old"), item("next")), feed.state.value.items)
        }

    @Test fun invalidation_cancels_inflight_work_and_prevents_restarting_stale_feeds() =
        runTest {
            var calls = 0
            val response = CompletableDeferred<List<WidgetMedia>>()
            val feed =
                WidgetFeed(backgroundScope, { 0L }, 3600, true) { _, _ ->
                    calls++
                    response.await()
                }
            feed.ensureLoaded()
            runCurrent()
            feed.close()
            response.complete(listOf(item("stale")))
            runCurrent()
            feed.refresh()
            runCurrent()
            assertTrue(
                feed.state.value.items
                    .isEmpty(),
            )
            assertFalse(feed.state.value.busy)
            assertEquals(1, calls)
        }
}
