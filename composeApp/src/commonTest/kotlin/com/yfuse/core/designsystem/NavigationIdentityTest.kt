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
    fun interrupted_push_removes_the_popped_target_after_settle() {
        val tracker = RouteRetentionTracker(initialTargetKey = "A")
        tracker.observe(targetKey = "B", previousRouteKey = "A")
        tracker.observe(targetKey = "A", previousRouteKey = "Home")

        assertEquals(
            setOf("B"),
            tracker.removalsWhenSettled(targetKey = "A", previousRouteKey = "Home"),
        )
    }

    @Test
    fun back_gesture_state_tracks_only_system_progress() {
        val state = BackGestureState()

        state.update(0.42f, BackGestureEdge.Right)
        assertTrue(state.active)
        assertEquals(0.42f, state.progress)
        assertEquals(BackGestureEdge.Right, state.edge)

        state.cancel()
        assertFalse(state.active)
        assertEquals(0f, state.progress)
        assertFalse(state.consumeCommittedGesture())
    }

    @Test
    fun completed_gesture_suppresses_exactly_one_followup_pop_animation() {
        val state = BackGestureState()
        state.update(0.9f, BackGestureEdge.Left)
        state.commit()

        assertTrue(state.consumeCommittedGesture())
        assertFalse(state.consumeCommittedGesture())

        // Hardware/back-button completion has no progress and must not masquerade as a swipe.
        state.commit()
        assertFalse(state.consumeCommittedGesture())
    }
}
