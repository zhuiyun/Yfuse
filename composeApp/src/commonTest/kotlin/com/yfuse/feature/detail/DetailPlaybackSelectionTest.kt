package com.yfuse.feature.detail

import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.SourceInfo
import com.yfuse.feature.player.PlaybackSelectionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailPlaybackSelectionTest {

    private val selection = PlaybackSelectionState(
        serverId = "remote",
        itemId = "episode-3",
        seriesId = "series",
        versionId = "4k",
    )

    @Test
    fun cross_server_selection_stays_pending_until_sources_arrive() {
        assertFalse(
            shouldApplyPlaybackSelection(
                selection = selection,
                appliedSelection = null,
                detailReady = true,
                playServerId = "local",
                playTargetReady = true,
                sources = emptyList(),
            ),
        )
        assertFalse(
            shouldApplyPlaybackSelection(
                selection = selection,
                appliedSelection = null,
                detailReady = true,
                playServerId = "local",
                playTargetReady = true,
                sources = listOf(remoteSource().copy(source = null)),
            ),
        )

        assertTrue(
            shouldApplyPlaybackSelection(
                selection = selection,
                appliedSelection = null,
                detailReady = true,
                playServerId = "local",
                playTargetReady = true,
                sources = listOf(remoteSource()),
            ),
        )
    }

    @Test
    fun same_server_selection_waits_for_playable_target_and_is_one_shot() {
        assertFalse(
            shouldApplyPlaybackSelection(
                selection = selection,
                appliedSelection = null,
                detailReady = true,
                playServerId = "remote",
                playTargetReady = false,
                sources = emptyList(),
            ),
        )
        assertTrue(
            shouldApplyPlaybackSelection(
                selection = selection,
                appliedSelection = null,
                detailReady = true,
                playServerId = "remote",
                playTargetReady = true,
                sources = emptyList(),
            ),
        )
        assertFalse(
            shouldApplyPlaybackSelection(
                selection = selection,
                appliedSelection = selection,
                detailReady = true,
                playServerId = "remote",
                playTargetReady = true,
                sources = emptyList(),
            ),
        )
    }

    private fun remoteSource() = ServerSource(
        serverId = "remote",
        serverName = "远端",
        isCurrent = false,
        source = SourceInfo("4K", null, null),
        reachable = true,
        itemId = "series",
    )
}
