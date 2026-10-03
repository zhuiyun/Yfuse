package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionBudgetTest {
    @Test
    fun power_saving_heat_or_little_memory_each_draw_as_calm() {
        assertEquals(MotionTheme.Classic, effectiveMotionTheme(MotionTheme.Classic, MotionBudget()))
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Classic, MotionBudget(powerSave = true)))
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Classic, MotionBudget(thermal = true)))
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Classic, MotionBudget(lowRam = true)))
    }

    @Test
    fun a_chosen_calm_theme_stays_calm_either_way() {
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Calm, MotionBudget()))
        assertEquals(MotionTheme.Calm, effectiveMotionTheme(MotionTheme.Calm, MotionBudget(powerSave = true)))
    }

    @Test
    fun heat_counts_from_moderate_as_android_defines_it() {
        assertFalse(thermallyConstrained(0))
        assertFalse(thermallyConstrained(1))
        assertTrue(thermallyConstrained(THERMAL_STATUS_MODERATE))
        assertTrue(thermallyConstrained(6))
        assertFalse(MotionBudget().reduced)
        assertTrue(MotionBudget(thermal = true).reduced)
    }
}
