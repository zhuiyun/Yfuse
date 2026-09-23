package com.yfuse.app

import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Dimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun floating_dock_clearance_grows_with_a_taller_dock() {
        val systemInset = 30.dp
        val tallDock = dockHeight(captionLine = 30.dp)

        assertEquals(
            tallDock - Dimens.tabBarHeight,
            floatingNavigationContentInset(systemInset, tallDock) - floatingNavigationContentInset(systemInset),
        )
    }

    @Test
    fun the_dock_is_its_token_height_at_the_default_font_scale() {
        // 11sp × 1.35 caption line at 1× — what AppTypography.caption resolves to on a default device.
        assertEquals(Dimens.tabBarHeight, dockHeight(captionLine = 14.85.dp))
    }

    @Test
    fun the_dock_grows_once_the_caption_no_longer_fits() {
        // 大号文字 (×1.12) on top of a 1.7× system font: the caption line alone is ~28dp.
        val scaled = dockHeight(captionLine = 28.3.dp)

        assertTrue(scaled > Dimens.tabBarHeight)
        assertTrue(scaled >= 34.dp + 28.3.dp)
    }

    @Test
    fun child_page_clearance_does_not_reserve_a_hidden_dock() {
        val systemInset = 30.dp

        assertEquals(systemInset + Dimens.sectionGap, systemNavigationContentInset(systemInset))
    }
}
