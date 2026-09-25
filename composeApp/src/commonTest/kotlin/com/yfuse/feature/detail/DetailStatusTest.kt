package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailStatusTest {
    @Test
    fun a_title_in_no_list_takes_no_row() {
        assertTrue(
            detailStatuses(
                favorite = false,
                watchLater = false,
                played = false,
                personalFavorite = false,
                personalWanted = false,
            ).isEmpty(),
        )
    }

    @Test
    fun only_the_states_that_hold_are_shown_and_always_in_one_order() {
        val statuses =
            detailStatuses(
                favorite = true,
                watchLater = false,
                played = true,
                personalFavorite = false,
                personalWanted = true,
            )

        assertEquals(listOf("已收藏", "已看完", "想看"), statuses.map { it.label })
    }
}
