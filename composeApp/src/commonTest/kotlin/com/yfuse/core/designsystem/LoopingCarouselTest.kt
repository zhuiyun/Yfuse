package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopingCarouselTest {

    @Test
    fun start_page_is_aligned_and_has_room_in_both_directions() {
        val page = loopingCarouselStartPage(itemCount = 5)

        assertEquals(0, loopingCarouselItemIndex(page, itemCount = 5))
        assertTrue(page > 5)
        assertTrue(page < LoopingCarouselPageCount - 5)
    }

    @Test
    fun pages_wrap_back_to_real_item_indices() {
        val start = loopingCarouselStartPage(itemCount = 4)

        assertEquals(3, loopingCarouselItemIndex(start - 1, itemCount = 4))
        assertEquals(0, loopingCarouselItemIndex(start, itemCount = 4))
        assertEquals(1, loopingCarouselItemIndex(start + 1, itemCount = 4))
        assertEquals(0, loopingCarouselItemIndex(start + 4, itemCount = 4))
    }

    @Test
    fun indicator_uses_the_shortest_route_across_the_loop_boundary() {
        val start = loopingCarouselStartPage(itemCount = 5)

        assertEquals(start - 1, loopingCarouselTargetPage(start, targetIndex = 4, itemCount = 5))
        assertEquals(start + 1, loopingCarouselTargetPage(start, targetIndex = 1, itemCount = 5))
    }

    @Test
    fun empty_and_single_item_carousels_stay_on_one_page() {
        assertEquals(1, loopingCarouselPageCount(itemCount = 0))
        assertEquals(1, loopingCarouselPageCount(itemCount = 1))
        assertEquals(0, loopingCarouselStartPage(itemCount = 1))
        assertEquals(0, loopingCarouselItemIndex(page = 42, itemCount = 0))
    }
}
