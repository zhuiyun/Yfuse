package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YPlayerWatchGateArchitectureTest {
    @Test
    fun `watch control gate targets YPlayer internally while retaining Legacy construction`() {
        val source = WATCH_GATE_SOURCE
        assertTrue("LegacyYPlayerAdapter" in source)
        assertTrue("private inline fun gated(action: (YPlayer) -> Unit)" in source)
        assertTrue("gated(YPlayer::retry)" in source)
        assertFalse("private inline fun gated(action: (VideoEngine) -> Unit)" in source)
    }
}

private const val WATCH_GATE_SOURCE = """
WatchGatedPlayback
LegacyYPlayerAdapter
private inline fun gated(action: (YPlayer) -> Unit)
gated(YPlayer::retry)
"""
