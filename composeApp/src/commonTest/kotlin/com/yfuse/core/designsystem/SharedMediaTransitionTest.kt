package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun a_back_during_the_morph_keeps_it_so_it_turns_round_where_it_is() {
        val controller = SharedMediaTransitionController()
        val poster = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")

        controller.begin(poster)
        controller.onPop(returnTo = null)

        assertEquals(poster, controller.activeKey)
        assertFalse(controller.popSuppressed)
    }

    @Test
    fun a_back_after_the_morph_settled_morphs_into_the_poster_it_came_from() {
        val controller = SharedMediaTransitionController()
        val poster = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")
        controller.begin(poster)
        controller.finish(poster)

        controller.onPop(returnTo = poster)

        assertEquals(poster, controller.activeKey)
        assertFalse(controller.popSuppressed)
    }

    @Test
    fun a_back_with_nowhere_to_return_to_suppresses_the_forward_morph() {
        val controller = SharedMediaTransitionController()
        val poster = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")
        controller.begin(poster)
        controller.finish(poster)

        controller.onPop(returnTo = null)

        assertNull(controller.activeKey)
        assertTrue(controller.popSuppressed)
    }

    @Test
    fun a_card_opening_the_page_keeps_the_poster_as_origin_but_calls_its_morph_off() {
        val controller = SharedMediaTransitionController()
        val poster = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")
        var opened = 0

        val key =
            controller.openFromCard {
                opened++
                controller.begin(poster)
            }

        assertEquals(1, opened)
        assertEquals(poster, key)
        assertNull(controller.activeKey)
        assertEquals(poster, controller.takeOrigin())
    }

    @Test
    fun a_card_does_not_carry_a_page_its_open_never_named() {
        val controller = SharedMediaTransitionController()
        val earlier = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")
        controller.noteOrigin(earlier)

        val key = controller.openFromCard {}

        assertNull(key)
        assertNull(controller.takeOrigin())
    }
}
