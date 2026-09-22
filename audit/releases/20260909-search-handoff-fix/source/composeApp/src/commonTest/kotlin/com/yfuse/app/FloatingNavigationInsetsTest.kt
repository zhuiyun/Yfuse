package com.yfuse.app

import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Dimens
import kotlin.test.Test
import kotlin.test.assertEquals

class FloatingNavigationInsetsTest {
    @Test
    fun floating_dock_clearance_tracks_the_system_navigation_inset() {
        val gestureInset = 24.dp
        val threeButtonInset = 48.dp

        assertEquals(
            threeButtonInset - gestureInset,
            floatingNavigationContentInset(threeButtonInset) -
                floatingNavigationContentInset(gestureInset),
        )
    }

    @Test
    fun floating_dock_clearance_contains_its_full_visible_geometry() {
        val systemInset = 30.dp

        assertEquals(
            systemInset + Dimens.tabBarInset + Dimens.tabBarHeight + Dimens.sectionGap,
            floatingNavigationContentInset(systemInset),
        )
    }

    @Test
    fun child_page_clearance_does_not_reserve_a_hidden_dock() {
        val systemInset = 30.dp

        assertEquals(systemInset + Dimens.sectionGap, systemNavigationContentInset(systemInset))
    }
}
