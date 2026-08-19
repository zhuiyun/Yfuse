package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MediaLazyItemKeyTest {
    @Test
    fun duplicate_media_ids_in_one_rail_have_unique_keys() {
        val first = mediaLazyItemKey("library:movies", 0, "107491")
        val duplicate = mediaLazyItemKey("library:movies", 1, "107491")

        assertNotEquals(first, duplicate)
        assertEquals(first, mediaLazyItemKey("library:movies", 0, "107491"))
    }

    @Test
    fun identical_positions_in_different_rails_have_distinct_keys() {
        assertNotEquals(
            mediaLazyItemKey("library:movies", 0, "107491"),
            mediaLazyItemKey("search:server-1", 0, "107491"),
        )
    }
}
