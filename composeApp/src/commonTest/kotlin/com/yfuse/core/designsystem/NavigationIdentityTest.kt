package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NavigationIdentityTest {

    @Test
    fun tab_reselection_only_targets_its_own_tab() {
        val event = TabReselection(tabIdentity = "Search", occurrence = 1L)

        assertTrue(event.targets("Search"))
        assertFalse(event.targets("Home"))
        assertFalse(event.targets(null))
    }

    @Test
    fun detail_route_identity_distinguishes_items_and_servers() {
        val first = detailRouteIdentity(serverId = "server-a", itemId = "movie-1")

        assertEquals(first, detailRouteIdentity(serverId = "server-a", itemId = "movie-1"))
        assertNotEquals(first, detailRouteIdentity(serverId = "server-a", itemId = "movie-2"))
        assertNotEquals(first, detailRouteIdentity(serverId = "server-b", itemId = "movie-1"))
    }

    @Test
    fun normal_push_keeps_the_outgoing_route_for_back_preview() {
        val tracker = RouteRetentionTracker(initialTargetKey = "A")

        tracker.observe(targetKey = "B", previousRouteKey = "A")

        assertEquals(
            emptySet(),
            tracker.removalsWhenSettled(targetKey = "B", previousRouteKey = "A"),
        )
    }

    @Test
    fun interrupted_push_still_removes_the_popped_target_after_settle() {
        val tracker = RouteRetentionTracker(initialTargetKey = "A")
        tracker.observe(targetKey = "B", previousRouteKey = "A")

        // Pop back before B ever reaches a settled transition frame.
        tracker.observe(targetKey = "A", previousRouteKey = "Home")

        assertEquals(
            setOf("B"),
            tracker.removalsWhenSettled(targetKey = "A", previousRouteKey = "Home"),
        )
    }

    @Test
    fun three_level_pop_freezes_the_committed_reveal_route_during_handoff() {
        // Before the pop: Home -> A -> B, so A is the route revealed by B's gesture.
        val tracker = PredictiveBackRevealRouteTracker(initialPrevious = "A")

        // After B is popped, A is the target and Home becomes `previous` immediately.
        assertEquals(
            "A",
            tracker.reveal(previous = "Home", frozen = true),
        )

        // Once A owns drawing, normal observation resumes for the next gesture.
        assertEquals(
            "Home",
            tracker.reveal(previous = "Home", frozen = false),
        )
    }

    @Test
    fun rapid_previous_changes_cannot_replace_a_committed_reveal_route() {
        val tracker = PredictiveBackRevealRouteTracker(initialPrevious = "Detail-A")

        assertEquals(
            "Detail-A",
            tracker.reveal(previous = "Home", frozen = true),
        )
        assertEquals(
            "Detail-A",
            tracker.reveal(previous = null, frozen = true),
        )
        assertEquals(
            "Detail-A",
            tracker.reveal(previous = "Unexpected", frozen = true),
        )

        assertEquals(
            "Library",
            tracker.reveal(previous = "Library", frozen = false),
        )
        assertEquals(
            "Library",
            tracker.reveal(previous = null, frozen = true),
        )
    }
}
