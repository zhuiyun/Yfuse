package com.yfuse.feature.library

import com.yfuse.core.designsystem.MediaSharedElementKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LibraryHomeSharedKeyTest {
    /** The key the detail page gives its hero, and so the one its morph and pull-down look for. */
    private val detailKey = MediaSharedElementKey("s1", "m1")

    private val places = listOf("hero", "hero:41", "history:0", "shelf:lib1:3", "shelf:lib2:0")

    @Test
    fun the_copy_last_opened_carries_the_detail_pages_key() {
        val opened = libraryHomeCopy("s1", "shelf:lib1:3", "m1")

        assertEquals(detailKey, libraryHomeSharedKey("s1", "shelf:lib1:3", "m1", opened))
    }

    @Test
    fun every_other_copy_of_the_title_has_a_key_of_its_own() {
        val opened = libraryHomeCopy("s1", "shelf:lib1:3", "m1")

        val keys = places.map { libraryHomeSharedKey("s1", it, "m1", opened) }

        assertEquals(places.size, keys.toSet().size)
        assertEquals(1, keys.count { it == detailKey })
    }

    @Test
    fun before_anything_is_opened_no_copy_claims_the_detail_pages_key() {
        val keys = places.map { libraryHomeSharedKey("s1", it, "m1", openedCopy = null) }

        assertTrue(keys.none { it == detailKey })
        assertEquals(places.size, keys.toSet().size)
    }

    @Test
    fun another_title_in_the_opened_place_keeps_its_own_key() {
        val opened = libraryHomeCopy("s1", "history:0", "m1")

        assertNotEquals(MediaSharedElementKey("s1", "m2"), libraryHomeSharedKey("s1", "history:0", "m2", opened))
    }

    @Test
    fun a_rail_reordered_under_the_detail_page_still_holds_the_opened_copy() {
        val before = listOf("m2", "m3", "m1")
        val opened = libraryHomeCopy("s1", libraryRailPlace("history", before, 2), "m1")

        // Played from its detail page, the title moves to the front of 播放记录.
        val after = listOf("m1", "m2", "m3")

        assertEquals(detailKey, libraryHomeSharedKey("s1", libraryRailPlace("history", after, 0), "m1", opened))
    }

    @Test
    fun a_title_repeated_within_a_rail_gives_each_copy_its_own_place() {
        val ids = listOf("m1", "m2", "m1")

        assertEquals("history", libraryRailPlace("history", ids, 0))
        assertEquals("history", libraryRailPlace("history", ids, 1))
        assertEquals("history:2", libraryRailPlace("history", ids, 2))
    }

    @Test
    fun a_copy_opened_on_another_server_is_not_this_one() {
        val opened = libraryHomeCopy("s1", "hero", "m1")

        assertNotEquals(MediaSharedElementKey("s2", "m1"), libraryHomeSharedKey("s2", "hero", "m1", opened))
    }
}
