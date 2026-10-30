package com.yfuse.core.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WidgetFeedRefreshTest {
    private val films = (1..30).map { WidgetMedia("$it", "Film $it") }

    @Test fun expiry_revalidates_once_without_clearing_content_or_showing_pull_progress() =
        runTest {
            var now = 0L
            var calls = 0
            val response = CompletableDeferred<List<WidgetMedia>>()
            val forced = mutableListOf<Boolean>()
            val feed =
                WidgetFeed(backgroundScope, { now }, 900, true) { _, force ->
                    forced += force
                    if (++calls == 1) films else response.await()
                }
            feed.ensureLoaded()
            runCurrent()
            now = 899_999L
            feed.refreshIfStale()
            assertEquals(1, calls)
            now++
            repeat(3) { feed.refreshIfStale() }
            runCurrent()
            assertEquals(listOf(false, true), forced)
            assertEquals(films, feed.state.value.items)
            assertTrue(feed.state.value.busy)
            assertFalse(feed.state.value.refreshing)
            response.complete(films.reversed())
            runCurrent()
            feed.refreshIfStale()
            runCurrent()
            assertEquals(2, calls)
            assertEquals(films.reversed(), feed.state.value.items)
        }

    @Test fun failed_background_refresh_keeps_selection_and_waits_for_retry() =
        runTest {
            var now = 0L
            var calls = 0
            val feed =
                WidgetFeed(backgroundScope, { now }, 1, false, recommendationLimit = 8) { _, _ ->
                    if (++calls == 2) error("offline") else films
                }
            feed.ensureLoaded()
            runCurrent()
            val selected = feed.state.value.homeItems
            now = 1_000L
            feed.refreshIfStale()
            runCurrent()
            now += 120_000L
            repeat(3) { feed.refreshIfStale() }
            runCurrent()
            assertEquals(2, calls)
            assertEquals(selected, feed.state.value.homeItems)
            assertEquals(films, feed.state.value.items)
            feed.retry()
            runCurrent()
            assertEquals(3, calls)
            assertNotEquals(selected, feed.state.value.homeItems)
        }

    @Test fun batches_cover_the_pool_before_repeating_and_leave_browse_order_untouched() =
        runTest {
            var calls = 0
            val feed =
                WidgetFeed(backgroundScope, { 0L }, 900, true, recommendationLimit = 8) { page, _ ->
                    calls++
                    if (page == 1) films else listOf(WidgetMedia("31", "Later"))
                }
            feed.ensureLoaded()
            runCurrent()
            val seen = mutableSetOf<String>()
            repeat(4) {
                val selected =
                    feed.state.value.homeItems
                        .orEmpty()
                assertEquals(8, selected.distinctBy { it.id }.size)
                seen += selected.map { it.id }
                feed.nextRecommendations()
            }
            assertEquals(films.map { it.id }.toSet(), seen)
            assertEquals(films, feed.state.value.items)
            assertEquals(1, calls)
            val selected = feed.state.value.homeItems
            feed.more(Int.MAX_VALUE)
            runCurrent()
            assertEquals(selected, feed.state.value.homeItems)
            assertEquals(2, feed.state.value.page)
            feed.refresh()
            assertTrue(feed.state.value.refreshing)
            runCurrent()
            assertFalse(feed.state.value.refreshing)
            assertEquals(1, feed.state.value.page)
            assertNotEquals(selected, feed.state.value.homeItems)
        }

    @Test fun ranked_feed_never_rotates_and_pagination_does_not_extend_first_page_freshness() =
        runTest {
            var now = 0L
            val feed =
                WidgetFeed(backgroundScope, { now }, 900, true) { page, _ ->
                    if (page == 1) films else listOf(WidgetMedia("31", "Later"))
                }
            feed.ensureLoaded()
            runCurrent()
            now = 800_000L
            feed.more(Int.MAX_VALUE)
            runCurrent()
            assertEquals(900_000L, feed.state.value.freshUntil)
            feed.nextRecommendations()
            assertEquals(null, feed.state.value.homeItems)
            assertEquals(
                films,
                feed.state.value.items
                    .take(30),
            )
        }
}
