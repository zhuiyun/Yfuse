package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowBrightnessMathTest {
    @Test
    fun systemSettingBecomesTheStartingWindowLevel() {
        assertEquals(1f, windowBrightnessForSystemSetting(255))
        assertEquals(51f / 255f, windowBrightnessForSystemSetting(51))
    }

    @Test
    fun aVendorScaleIsReadAgainstItsOwnMaximum() {
        assertEquals(600f / 2_047f, windowBrightnessForSystemSetting(600, maximum = 2_047))
        assertEquals(1f, windowBrightnessForSystemSetting(4_095, maximum = 4_095))
    }

    @Test
    fun startingLevelStaysInsideTheRangeADragCanReach() {
        assertEquals(0.02f, windowBrightnessForSystemSetting(0))
    }

    @Test
    fun aReadingPastTheKnownMaximumKeepsTheMidpointInsteadOfFullBrightness() {
        assertEquals(0.5f, windowBrightnessForSystemSetting(1_023))
    }

    @Test
    fun unreadableSettingKeepsTheMidpoint() {
        assertEquals(0.5f, windowBrightnessForSystemSetting(null))
    }
}
