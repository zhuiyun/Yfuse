package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedMediaTransitionTest {

    @Test
    fun only_the_matching_forward_transition_can_clear_the_active_artwork() {
        val controller = SharedMediaTransitionController()
        val first = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")
        val second = MediaSharedElementKey(serverId = "server-a", itemId = "movie-2")

        controller.begin(first)
        controller.finish(second)

        assertEquals(first, controller.activeKey)
        controller.finish(first)
        assertNull(controller.activeKey)
    }

    @Test
    fun a_new_forward_tap_replaces_a_stale_transition_key() {
        val controller = SharedMediaTransitionController()
        val first = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")
        val second = MediaSharedElementKey(serverId = "server-b", itemId = "movie-1")

        controller.begin(first)
        controller.begin(second)

        assertEquals(second, controller.activeKey)
    }

    @Test
    fun a_pop_suppresses_the_overlay_until_another_forward_tap() {
        val controller = SharedMediaTransitionController()
        val first = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")
        val second = MediaSharedElementKey(serverId = "server-a", itemId = "movie-2")

        controller.begin(first)
        controller.suppressForPop()

        assertEquals(true, controller.popSuppressed)
        controller.begin(second)
        assertEquals(false, controller.popSuppressed)
        assertEquals(second, controller.activeKey)
    }
}
