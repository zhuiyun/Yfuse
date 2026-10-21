package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionBudgetTest {
    @Test
    fun power_saving_and_little_memory_draw_as_calm_but_heat_does_not() {
        assertEquals(MotionTheme.Classic, effectiveMotionTheme(MotionTheme.Classic, MotionBudget()))
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Classic, MotionBudget(powerSave = true)))
        assertEquals(MotionTheme.Classic, effectiveMotionTheme(MotionTheme.Classic, MotionBudget(thermal = true)))
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Classic, MotionBudget(lowRam = true)))
    }

    @Test
    fun a_chosen_calm_theme_stays_calm_either_way() {
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Calm, MotionBudget()))
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Calm, MotionBudget(powerSave = true)))
    }

    @Test
    fun reported_heat_keeps_every_motion_theme_available() {
        assertFalse(isDeviceWarm(0))
        assertFalse(isDeviceWarm(1))
        assertTrue(isDeviceWarm(THERMAL_STATUS_MODERATE))
        assertTrue(isDeviceWarm(6))
        assertFalse(MotionBudget().reduced)
        for (status in 0..6) {
            val budget = MotionBudget(thermal = isDeviceWarm(status))
            assertFalse(budget.reduced)
            for (theme in MotionTheme.entries) {
                assertEquals(theme, effectiveMotionTheme(theme, budget))
            }
        }
    }
}
