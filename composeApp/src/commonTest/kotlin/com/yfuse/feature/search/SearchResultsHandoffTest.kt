package com.yfuse.feature.search

import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchResultsHandoffTest {
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
