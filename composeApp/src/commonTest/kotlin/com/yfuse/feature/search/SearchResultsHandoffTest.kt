package com.yfuse.feature.search

import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.data.aggregateCrossServerMedia
import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchResultsHandoffTest {
    @Test
    fun incremental_server_results_do_not_fade_an_existing_card_back_out() {
        val order = SearchRevealOrder()
        val firstHits = listOf(CrossServerMediaHit("first", "First", movie("original")))
        val original = aggregateCrossServerMedia(firstHits).single()
        val elapsed = 150f / 680f
        val before = order.progress(elapsed, original.identity, index = 1)
        assertTrue(before > 0.1f)

        val laterHits =
            listOf("A", "B").map { title ->
                CrossServerMediaHit(
                    "second",
                    "Second",
                    movie(title).copy(title = title, communityRating = 9.0),
                )
            }
        val updated = aggregateCrossServerMedia(firstHits + laterHits)
        val newIndex = updated.indexOfFirst { it.identity == original.identity } + 1
        assertEquals(3, newIndex)
        // The old position-based delay nearly hid this half-visible card again.
        assertTrue(searchRevealProgress(elapsed, newIndex) < before / 10f)
        assertEquals(before, order.progress(elapsed, original.identity, newIndex))
        assertTrue(order.progress(200f / 680f, original.identity, newIndex) >= before)
        assertEquals(1f, order.progress(1f, original.identity, newIndex))
    }

    @Test
    fun server_groups_keep_their_delay_when_earlier_servers_finish() {
        val order = SearchRevealOrder()
        val elapsed = 150f / 680f
        val before = order.progress(elapsed, "server-results-second", index = 1)
        // Completion order differs from the configured server order.
        order.progress(elapsed, "server-results-first", index = 1)
        assertEquals(before, order.progress(elapsed, "server-results-second", index = 2))
        // Moving the same group back toward the front cannot jump its progress either.
        assertEquals(before, order.progress(elapsed, "server-results-second", index = 0))
    }

    @Test
    fun later_rows_join_the_shared_clock_and_a_new_handoff_uses_the_new_order() {
        val order = SearchRevealOrder()
        val elapsed = 150f / 680f
        order.progress(elapsed, "original", index = 1)
        assertEquals(searchRevealProgress(elapsed, 5), order.progress(elapsed, "late", index = 5))
        for (index in 1..20) {
            assertEquals(1f, order.progress(1f, "page-$index", index))
        }
        val nextHandoff = SearchRevealOrder()
        assertEquals(searchRevealProgress(elapsed, 3), nextHandoff.progress(elapsed, "original", index = 3))
    }

    @Test
    fun first_result_is_visible_before_the_later_rows_begin_to_enter() {
        val earlyTime = 150f / 680f
        assertTrue(searchRevealProgress(earlyTime, 1) > 0.1f)
        assertEquals(0f, searchRevealProgress(earlyTime, 5))
        assertTrue(searchRevealProgress(400f / 680f, 5) > 0f)
        for (index in listOf(0, 1, 5, 30)) {
            assertEquals(1f, searchRevealProgress(1f, index))
        }
    }

    @Test
    fun sweep_is_only_for_loading_to_results_not_filtering_errors_or_restored_content() {
        SearchResultsPhase.entries.forEach { before ->
            SearchResultsPhase.entries.forEach { after ->
                val handoff = SearchResultsHandoff(before)
                assertEquals(
                    before == SearchResultsPhase.Loading && after == SearchResultsPhase.Results,
                    handoff.shouldSweep(after, moving = true),
                )
                assertFalse(handoff.shouldSweep(after, moving = false))
            }
        }
    }

    @Test
    fun first_search_reveals_only_when_content_or_a_terminal_message_arrives() {
        val handoff = SearchResultsHandoff(SearchResultsPhase.Idle)
        val loading = SearchState(query = "沙丘", searchedQuery = "沙丘", loading = true)
        assertEquals(SearchResultsPhase.Loading, loading.resultsPhase())
        assertFalse(handoff.shouldReveal(loading.resultsPhase(), moving = true))
        handoff.committed(loading.resultsPhase())

        val emptyFirstServer = loading.copy(groups = listOf(ServerSearchGroup("empty", "空服务器")))
        assertEquals(SearchResultsPhase.Loading, emptyFirstServer.resultsPhase())
        assertFalse(handoff.shouldReveal(emptyFirstServer.resultsPhase(), moving = true))
        handoff.committed(emptyFirstServer.resultsPhase())

        val firstResults = emptyFirstServer.copy(groups = emptyFirstServer.groups + results().groups)
        assertEquals(SearchResultsPhase.Results, firstResults.resultsPhase())
        assertTrue(handoff.shouldReveal(firstResults.resultsPhase(), moving = true))
        handoff.committed(firstResults.resultsPhase())
        assertFalse(handoff.shouldReveal(firstResults.copy(loading = false).resultsPhase(), moving = true))
    }

    @Test
    fun pagination_server_updates_and_refresh_with_retained_results_do_not_replay() {
        val original = results()
        val handoff = SearchResultsHandoff(original.resultsPhase())
        val group = original.groups.single()
        val updates =
            listOf(
                original.copy(loading = true),
                original.copy(groups = listOf(group.copy(loadingMore = true))),
                original.copy(groups = listOf(group.copy(items = group.items + movie("second")))),
                original.copy(groups = original.groups + ServerSearchGroup("offline", "离线", error = "不可用")),
                original.copy(groups = listOf(group.copy(loadMoreError = "重试加载更多"))),
            )
        updates.forEach { state ->
            assertEquals(SearchResultsPhase.Results, state.resultsPhase())
            assertFalse(handoff.shouldReveal(state.resultsPhase(), moving = true))
            handoff.committed(state.resultsPhase())
        }
        assertFalse(handoff.shouldReveal(original.resultsPhase(), moving = true))
    }

    @Test
    fun empty_and_error_retry_paths_reveal_the_replacement_once() {
        val empty = SearchState(query = "沙丘", searchedQuery = "沙丘")
        val failed = empty.copy(error = "连接失败")
        assertEquals(SearchResultsPhase.Empty, empty.resultsPhase())
        assertEquals(SearchResultsPhase.Error, failed.resultsPhase())
        listOf(empty, failed).forEach { state ->
            val handoff = SearchResultsHandoff(SearchResultsPhase.Loading)
            assertTrue(handoff.shouldReveal(state.resultsPhase(), moving = true))
            handoff.committed(state.resultsPhase())
            assertFalse(handoff.shouldReveal(state.resultsPhase(), moving = true))
            val retry = state.copy(loading = true, error = null)
            assertFalse(handoff.shouldReveal(retry.resultsPhase(), moving = true))
            handoff.committed(retry.resultsPhase())
            assertTrue(handoff.shouldReveal(results().resultsPhase(), moving = true))
        }
    }

    @Test
    fun hidden_and_reduced_motion_completions_are_consumed_without_a_return_animation() {
        listOf(SearchResultsPhase.Results, SearchResultsPhase.Error, SearchResultsPhase.Empty).forEach { terminal ->
            val handoff = SearchResultsHandoff(SearchResultsPhase.Loading)
            assertFalse(handoff.shouldReveal(terminal, moving = false))
            handoff.committed(terminal)
            assertFalse(handoff.shouldReveal(terminal, moving = true))
        }
    }

    @Test
    fun restored_content_does_not_reenter_and_clearing_does_not_hide_the_search_field() {
        SearchResultsPhase.entries.forEach { phase ->
            assertFalse(SearchResultsHandoff(phase).shouldReveal(phase, moving = true))
        }
        val handoff = SearchResultsHandoff(SearchResultsPhase.Results)
        val cleared = SearchState()
        assertEquals(SearchResultsPhase.Idle, cleared.resultsPhase())
        assertFalse(handoff.shouldReveal(cleared.resultsPhase(), moving = true))
    }

    @Test
    fun filtering_to_no_visible_items_changes_phase_without_changing_stored_results() {
        val original = results()
        val filtered = original.copy(type = SearchType.Series)
        assertEquals(SearchResultsPhase.Empty, filtered.resultsPhase())
        assertEquals(original.groups, filtered.groups)
        val handoff = SearchResultsHandoff(original.resultsPhase())
        assertTrue(handoff.shouldReveal(filtered.resultsPhase(), moving = true))
        handoff.committed(filtered.resultsPhase())
        assertTrue(handoff.shouldReveal(original.resultsPhase(), moving = true))
    }

    private fun results(): SearchState =
        SearchState(
            query = "沙丘",
            searchedQuery = "沙丘",
            groups = listOf(ServerSearchGroup("server", "服务器", listOf(movie("first")))),
        )

    private fun movie(id: String): MediaItem =
        MediaItem(
            id = id,
            title = "沙丘",
            subtitle = null,
            type = "Movie",
            posterItemId = id,
            posterTag = null,
            backdropItemId = null,
            backdropTag = null,
            playedPercentage = null,
        )
}
