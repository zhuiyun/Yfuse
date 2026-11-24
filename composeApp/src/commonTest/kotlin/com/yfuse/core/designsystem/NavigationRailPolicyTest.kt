package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationRailPolicyTest {
    @Test
    fun railRequiresExpandedWidthAndRoomForVerticalNavigation() {
        assertFalse(useNavigationRail(412.dp, 915.dp))
        assertFalse(useNavigationRail(839.dp, 900.dp))
        assertFalse(useNavigationRail(915.dp, 479.dp))
        assertTrue(useNavigationRail(840.dp, 480.dp))
        assertTrue(useNavigationRail(1280.dp, 800.dp))
    }

    @Test
    fun dualPaneSupportsUnfoldedFoldablesAndPortraitTabletsWithoutShortPhones() {
        assertFalse(useDualPaneLayout(412.dp, 915.dp))
        assertFalse(useDualPaneLayout(800.dp, 390.dp))
        assertFalse(useDualPaneLayout(680.dp, 840.dp))
        assertTrue(useDualPaneLayout(700.dp, 840.dp))
        assertTrue(useDualPaneLayout(800.dp, 680.dp))
        assertTrue(useDualPaneLayout(840.dp, 1200.dp))
        assertTrue(useDualPaneLayout(1280.dp, 800.dp))
    }
}
