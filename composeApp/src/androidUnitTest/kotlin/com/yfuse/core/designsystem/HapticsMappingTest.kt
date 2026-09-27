package com.yfuse.core.designsystem

import android.view.HapticFeedbackConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HapticsMappingTest {
    @Test
    fun android14UsesTheSegmentGestureAndToggleConstants() {
        val sdk = 34
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticFeedbackConstant(HapticSignal.Tick, sdk))
        assertEquals(
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
            hapticFeedbackConstant(HapticSignal.FrequentTick, sdk),
        )
        assertEquals(
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
            hapticFeedbackConstant(HapticSignal.Threshold, sdk),
        )
        assertEquals(
            HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
            hapticFeedbackConstant(HapticSignal.ThresholdRelease, sdk),
        )
        assertEquals(HapticFeedbackConstants.DRAG_START, hapticFeedbackConstant(HapticSignal.DragStart, sdk))
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, hapticFeedbackConstant(HapticSignal.ToggleOn, sdk))
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, hapticFeedbackConstant(HapticSignal.ToggleOff, sdk))
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticFeedbackConstant(HapticSignal.Confirm, sdk))
    }

    @Test
    fun android11To13FallBackToGestureStartAndEnd() {
        val sdk = 30
        assertEquals(HapticFeedbackConstants.GESTURE_START, hapticFeedbackConstant(HapticSignal.Threshold, sdk))
        assertEquals(HapticFeedbackConstants.GESTURE_END, hapticFeedbackConstant(HapticSignal.ThresholdRelease, sdk))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(HapticSignal.Tick, sdk))
        assertEquals(HapticFeedbackConstants.TEXT_HANDLE_MOVE, hapticFeedbackConstant(HapticSignal.FrequentTick, sdk))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticFeedbackConstant(HapticSignal.DragStart, sdk))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(HapticSignal.ToggleOn, sdk))
        assertEquals(HapticFeedbackConstants.REJECT, hapticFeedbackConstant(HapticSignal.Reject, sdk))
    }

    @Test
    fun android8To10StaySilentForAReleaseRatherThanTickTwice() {
        assertNull(hapticFeedbackConstant(HapticSignal.ThresholdRelease, 28))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(HapticSignal.Threshold, 28))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, hapticFeedbackConstant(HapticSignal.Confirm, 28))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticFeedbackConstant(HapticSignal.Reject, 28))
        assertEquals(HapticFeedbackConstants.TEXT_HANDLE_MOVE, hapticFeedbackConstant(HapticSignal.FrequentTick, 27))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFeedbackConstant(HapticSignal.FrequentTick, 26))
    }

    @Test
    fun everySignalMapsOnEveryVersionTheAppSupportsExceptTheOldRelease() {
        (26..37).forEach { sdk ->
            HapticSignal.entries.forEach { signal ->
                val constant = hapticFeedbackConstant(signal, sdk)
                if (!(signal == HapticSignal.ThresholdRelease && sdk < 30)) {
                    assertEquals(true, constant != null, "$signal on $sdk")
                }
            }
        }
    }
}
