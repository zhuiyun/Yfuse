package com.yfuse.core.designsystem

import androidx.navigationevent.NavigationEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class OfficialNavDisplayTransitionTest {
    @Test
    fun left_edge_moves_the_outgoing_page_right() {
        assertEquals(1080, backExitOffset(1080, NavigationEvent.EDGE_LEFT))
    }

    @Test
    fun right_edge_moves_the_outgoing_page_left() {
        assertEquals(-1080, backExitOffset(1080, NavigationEvent.EDGE_RIGHT))
    }

    @Test
    fun committed_back_uses_the_standard_left_edge_direction() {
        assertEquals(1080, backExitOffset(1080, NavigationEvent.EDGE_NONE))
    }
}
