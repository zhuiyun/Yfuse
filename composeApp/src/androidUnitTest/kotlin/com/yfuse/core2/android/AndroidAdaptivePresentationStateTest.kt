package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AndroidAdaptivePresentationStateTest {
    @Test
    fun `second period maps local progress and buffering to whole title`() {
        val target = YAdaptivePlaybackTarget("root", "period-two", 2_000L, 60_000L, 120_000L, 2L, 120_000L, 3L)
        val local = YPlayerState(positionMs = 2_000L, bufferedPositionMs = 8_000L, durationMs = 60_000L)
        val mapped = mapAdaptivePresentationState(local, target)
        assertEquals(62_000L, mapped.positionMs)
        assertEquals(68_000L, mapped.bufferedPositionMs)
        assertEquals(120_000L, mapped.durationMs)
        assertEquals(local.diagnostics, mapped.diagnostics)
    }

    @Test
    fun `unknown presentation duration uses period end and ordinary sources retain state`() {
        val local = YPlayerState(positionMs = 11_000L, durationMs = 10_000L)
        val target = YAdaptivePlaybackTarget("root", "period", 0L, 60_000L, 0L, 1L, null, 1L)
        assertEquals(70_000L, mapAdaptivePresentationState(local, target).positionMs)
        assertEquals(70_000L, mapAdaptivePresentationState(local, target).durationMs)
        assertSame(local, mapAdaptivePresentationState(local, null))
    }
}
