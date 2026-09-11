package com.yfuse.feature.search

import com.yfuse.core.designsystem.Motion
import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchResultsHandoffTest {
    @Test
    fun incremental_server_results_do_not_fade_an_existing_card_back_out() {
        val schedule = SearchRevealSchedule()
        schedule.open(SearchRevealBatch(startMs = 0f), fresh = true)
        schedule.progress("heading", 0f)
        val before = schedule.progress("original", 150f)
        assertTrue(before > 0.1f)

        // A second server lands while the first is still entering: same phase, new key.
        schedule.open(SearchRevealBatch(startMs = 150f), fresh = false)
        assertEquals(before, schedule.progress("original", 150f))
        assertTrue(schedule.progress("original", 200f) > before)
        // The rows it adds enter from nothing, on their own clock, after the rows already there.
        assertEquals(0f, schedule.progress("late-a", 150f))
        assertEquals(0f, schedule.progress("late-b", 150f + SEARCH_ROW_STAGGER_MS))
        assertTrue(schedule.progress("late-a", 150f + SEARCH_ROW_STAGGER_MS) > 0f)
        assertEquals(1f, schedule.progress("original", 2000f))
        assertEquals(1f, schedule.progress("late-b", 2000f))
    }

    @Test
    fun rows_keep_their_slot_when_results_reorder_and_later_pages_do_not_enter() {
        val schedule = SearchRevealSchedule()
        schedule.open(SearchRevealBatch(startMs = 0f), fresh = true)
        val first = schedule.progress("server-results-second", 150f)
        schedule.progress("server-results-first", 150f)
        // Drawn in a different order later, the row still owns the slot it entered with.
        assertEquals(first, schedule.progress("server-results-second", 150f))
        assertTrue(schedule.progress("server-results-first", 150f) < first)
        // Rows scrolled into view after the batch finished are simply there.
        val settled = SearchRevealBatch(startMs = 0f).endMs + 1f
        for (index in 1..20) {
            assertEquals(1f, schedule.progress("page-$index", settled))
        }
    }

    @Test
    fun a_fresh_phase_forgets_the_page_and_an_in_place_change_does_not() {
        val schedule = SearchRevealSchedule()
        schedule.open(SearchRevealBatch(startMs = 0f), fresh = true)
        assertEquals(1f, schedule.progress("row", 2000f))
        schedule.open(SearchRevealBatch(startMs = 2000f), fresh = false)
        assertEquals(1f, schedule.progress("row", 2000f))
        schedule.open(SearchRevealBatch(startMs = 3000f), fresh = true)
        assertEquals(0f, schedule.progress("row", 3000f))
        assertTrue(schedule.lifts("row"))
    }

    @Test
    fun rows_seen_before_any_batch_are_settled_and_never_enter_later() {
        val schedule = SearchRevealSchedule()
        assertEquals(1f, schedule.progress("restored", 0f))
        assertFalse(schedule.lifts("restored"))
        schedule.open(SearchRevealBatch(startMs = 500f), fresh = false)
        assertEquals(1f, schedule.progress("restored", 500f))
        assertEquals(0f, schedule.progress("added", 500f))
    }

    @Test
    fun message_batches_fade_together_without_lift() {
        val batch = SearchRevealBatch(startMs = 0f, rowMs = Motion.STATE_HANDOFF, staggered = false)
        assertFalse(batch.lift)
        assertEquals(Motion.STATE_HANDOFF.toFloat(), batch.endMs)
        val schedule = SearchRevealSchedule()
        schedule.open(batch, fresh = true)
        val heading = schedule.progress("heading", 75f)
        assertEquals(heading, schedule.progress("empty", 75f))
        assertTrue(heading > 0f && heading < 1f)
        assertFalse(schedule.lifts("empty"))
    }

    @Test
    fun first_result_is_visible_before_the_later_rows_begin_to_enter() {
        assertTrue(searchRowProgress(150f, 1) > 0.1f)
        assertEquals(0f, searchRowProgress(150f, 5))
        assertTrue(searchRowProgress(400f, 5) > 0f)
        for (slot in listOf(0, 1, 5, 30)) {
            assertEquals(1f, searchRowProgress(SearchRevealBatch(0f).endMs, slot))
        }
    }

    @Test
    fun later_visible_rows_keep_the_full_fifty_five_millisecond_step_and_the_row_duration() {
        val row = 9
        assertEquals(0f, searchRowProgress(row * 55 - 1f, row))
        assertTrue(searchRowProgress(row * 55 + 50f, row) > 0f)
        assertTrue(searchRowProgress(row * 55 + SEARCH_ROW_MS - 1f, row) < 1f)
        assertEquals(1f, searchRowProgress(row * 55f + SEARCH_ROW_MS, row))
    }

    @Test
    fun only_a_landing_from_the_skeleton_sweeps_the_page() {
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
        assertTrue(handoff.isFresh(firstResults.resultsPhase()))
        handoff.committed(firstResults.resultsPhase())
        assertFalse(handoff.shouldReveal(firstResults.copy(loading = false).resultsPhase(), moving = true))
    }

    @Test
    fun phase_only_calls_need_a_presentation_key_to_identify_in_place_changes() {
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
        assertTrue(handoff.isFresh(filtered.resultsPhase()))
        handoff.committed(filtered.resultsPhase())
        assertTrue(handoff.shouldReveal(original.resultsPhase(), moving = true))
    }

    @Test
    fun result_and_filter_changes_add_rows_without_replaying_the_landing() {
        val original = results()
        val key = original.presentationKey()
        val handoff = SearchResultsHandoff(original.resultsPhase(), key)
        val expanded =
            original.copy(
                groups = listOf(original.groups.single().copy(items = listOf(movie("first"), movie("second")))),
            )
        assertTrue(handoff.shouldReveal(expanded.resultsPhase(), true, expanded.presentationKey()))
        assertFalse(handoff.isFresh(expanded.resultsPhase()))
        assertFalse(handoff.shouldSweep(expanded.resultsPhase(), true))
        handoff.committed(expanded.resultsPhase(), expanded.presentationKey())
        assertFalse(handoff.shouldReveal(expanded.resultsPhase(), true, expanded.presentationKey()))
        val filtered = expanded.copy(sort = SearchSort.Name)
        assertTrue(handoff.shouldReveal(filtered.resultsPhase(), true, filtered.presentationKey()))
        assertFalse(handoff.isFresh(filtered.resultsPhase()))
        val people = filtered.copy(people = listOf(PersonHit("server", "服务器", "p1", "演员", null)))
        handoff.committed(filtered.resultsPhase(), filtered.presentationKey())
        assertTrue(handoff.shouldReveal(people.resultsPhase(), true, people.presentationKey()))
        assertFalse(handoff.shouldSweep(people.resultsPhase(), true))
    }

    @Test
    fun typing_and_transport_flags_do_not_change_presentation_identity() {
        val original = results()
        assertEquals(original.presentationKey(), original.copy(query = "new typing", loading = true).presentationKey())
        assertEquals(
            original.presentationKey(),
            original.copy(groups = listOf(original.groups.single().copy(loadingMore = true))).presentationKey(),
        )
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
