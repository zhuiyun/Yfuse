package com.yfuse.feature.handoff

import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.feature.player.PlayerMediaItem
import kotlin.test.Test
import kotlin.test.assertNull

class HandoffPlaybackEnginePolicyTest {
    @Test
    fun supported_local_playback_can_be_prepared_for_either_engine() {
        val items = listOf(PlayerMediaItem("movie", "file:///fixture/movie.mp4", "", "Movie"))
        for (selection in PlaybackEngineSelection.entries) {
            assertNull(handoffPlaybackBlockReason(selection, items, 0), selection.name)
        }
    }
}
