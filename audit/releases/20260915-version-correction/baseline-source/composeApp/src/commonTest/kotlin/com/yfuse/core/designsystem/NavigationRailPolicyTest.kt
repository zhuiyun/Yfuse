package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse

class NavigationRailPolicyTest {
    @Test
    fun portrait_windows_keep_the_floating_bottom_dock() {
        assertFalse(useNavigationRail(width = 412.dp, height = 915.dp))
        assertFalse(useNavigationRail(width = 840.dp, height = 1280.dp))
        assertFalse(useNavigationRail(width = 1000.dp, height = 1340.dp))
    }

    @Test
    fun landscape_windows_keep_the_same_floating_bottom_dock() {
        assertFalse(useNavigationRail(width = 732.dp, height = 412.dp))
        assertFalse(useNavigationRail(width = 915.dp, height = 412.dp))
        assertFalse(useNavigationRail(width = 1280.dp, height = 800.dp))
        assertFalse(useNavigationRail(width = 1440.dp, height = 900.dp))
        assertFalse(useNavigationRail(width = 1600.dp, height = 800.dp))
        assertFalse(useNavigationRail(width = 1920.dp, height = 1080.dp))
        assertFalse(useNavigationRail(width = 1200.dp, height = 1200.dp))
    }
}
