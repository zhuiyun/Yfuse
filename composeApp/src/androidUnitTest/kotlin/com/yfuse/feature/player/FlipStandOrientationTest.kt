package com.yfuse.feature.player

import android.content.pm.ActivityInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlipStandOrientationTest {
    private val landscape = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    private val followsPhone = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    private val pinned = ActivityInfo.SCREEN_ORIENTATION_LOCKED

    @Test
    fun a_flip_phone_standing_half_open_follows_the_phone_until_it_opens_flat() {
        val stand = FlipStandOrientation()
        assertEquals(followsPhone, stand.onPosture(landscape, halfOpen = true, flipHinge = true, mayBorrow = true))
        // Come upright, the hinge lies across the window: still standing.
        assertNull(stand.onPosture(followsPhone, halfOpen = true, flipHinge = false, mayBorrow = true))
        assertEquals(landscape, stand.onPosture(followsPhone, halfOpen = false, flipHinge = false, mayBorrow = true))
        assertNull(stand.onLeave(landscape))
    }

    @Test
    fun a_fold_already_across_the_landscape_window_is_left_to_the_tabletop_layout() {
        // A book-style foldable on its side: its hinge already lies across the landscape window.
        assertNull(FlipStandOrientation().onPosture(landscape, halfOpen = true, flipHinge = false, mayBorrow = true))
    }

    @Test
    fun tablets_televisions_and_rotation_lock_are_left_alone() {
        val stand = FlipStandOrientation()
        assertNull(stand.onPosture(landscape, halfOpen = true, flipHinge = true, mayBorrow = false))
        // A tablet already follows the device.
        assertNull(stand.onPosture(followsPhone, halfOpen = true, flipHinge = true, mayBorrow = true))
        // 旋转锁 has pinned the window.
        assertNull(stand.onPosture(pinned, halfOpen = true, flipHinge = true, mayBorrow = true))
        assertNull(stand.onLeave(landscape))
    }

    @Test
    fun rotation_lock_pinning_the_upright_window_keeps_it_until_let_go() {
        val stand = FlipStandOrientation()
        stand.onPosture(landscape, halfOpen = true, flipHinge = true, mayBorrow = true)
        // Pinned upright, then opened flat: the pin holds.
        assertNull(stand.onPosture(pinned, halfOpen = false, flipHinge = false, mayBorrow = false))
        // Let go, 旋转锁 hands back FULL_USER, and the landscape follows.
        assertEquals(landscape, stand.onPosture(followsPhone, halfOpen = false, flipHinge = false, mayBorrow = true))
    }

    @Test
    fun leaving_gives_back_only_what_is_still_borrowed() {
        val standing = FlipStandOrientation()
        standing.onPosture(landscape, halfOpen = true, flipHinge = true, mayBorrow = true)
        assertEquals(landscape, standing.onLeave(followsPhone))
        assertNull(standing.onLeave(followsPhone))
        val pinnedUpright = FlipStandOrientation()
        pinnedUpright.onPosture(landscape, halfOpen = true, flipHinge = true, mayBorrow = true)
        assertNull(pinnedUpright.onLeave(pinned))
    }
}
