package com.yfuse.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockInteractiveEffectsTest {
    @Test
    fun lensHoverMagnifiesClosestIconOnlyWhileDragging() {
        assertEquals(1f, dockLensHoverScale(tabIndex = 1, lensCenterIndex = 1f, dragging = false))
        val centered = dockLensHoverScale(tabIndex = 1, lensCenterIndex = 1f, dragging = true)
        val adjacent = dockLensHoverScale(tabIndex = 2, lensCenterIndex = 1f, dragging = true)
        val distant = dockLensHoverScale(tabIndex = 4, lensCenterIndex = 1f, dragging = true)

        assertTrue(centered > 1.15f, "centered icon should magnify ~1.16x: $centered")
        assertTrue(adjacent in 1.0f..1.05f, "adjacent icon should lift only slightly: $adjacent")
        assertTrue(distant < 1.001f, "distant icon should stay at rest: $distant")
    }
}
