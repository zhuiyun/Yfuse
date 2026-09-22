package com.yfuse.feature.player

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivePlaybackTest {
    @AfterTest
    fun tearDown() {
        ActivePlayback.clear()
    }

    @Test
    fun closeClearsVisibleStateBeforeInvokingBoundCloseAction() {
        var stateWasAlreadyCleared = false
        ActivePlayback.bind(
            toggle = {},
            open = {},
            close = { stateWasAlreadyCleared = !ActivePlayback.state.value.active },
        )
        ActivePlayback.update(
            title = "Episode",
            playback = PlaybackState(playing = true, positionMs = 1_000L, durationMs = 2_000L),
        )

        ActivePlayback.close()

        assertTrue(stateWasAlreadyCleared)
        assertFalse(ActivePlayback.state.value.active)
    }

    @Test
    fun rebindingReplacesTheWholeActionSnapshot() {
        val calls = mutableListOf<String>()
        ActivePlayback.bind(
            toggle = { calls += "old-toggle" },
            open = { calls += "old-open" },
            close = { calls += "old-close" },
        )
        ActivePlayback.bind(
            toggle = { calls += "new-toggle" },
            open = { calls += "new-open" },
            close = { calls += "new-close" },
        )

        ActivePlayback.toggle()
        ActivePlayback.open()
        ActivePlayback.close()

        assertEquals(listOf("new-toggle", "new-open", "new-close"), calls)
    }
}
