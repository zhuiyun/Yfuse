package com.yfuse.feature.handoff

import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HandoffPlaybackEnginePolicyTest {
    @Test
    fun a_ycore_format_rejection_does_not_veto_an_explicit_senplayer_handoff() {
        val version =
            PlayerMediaVersion(
                "v",
                "AVI",
                "",
                "http://media/movie.avi",
                "",
                "",
                container = "AVI",
                sourceVideoCodec = "Theora",
            )
        val items =
            listOf(PlayerMediaItem("movie", version.url, "", "Movie", versions = listOf(version), versionId = "v"))
        assertNotNull(handoffPlaybackBlockReason(PlaybackEngineSelection.Auto, items, 0))
        assertNotNull(handoffPlaybackBlockReason(PlaybackEngineSelection.LockYCore, items, 0))
        assertNull(handoffPlaybackBlockReason(PlaybackEngineSelection.LockSenPlayer, items, 0))
    }

    @Test
    fun supported_local_playback_can_be_prepared_for_either_engine() {
        val items = listOf(PlayerMediaItem("movie", "file:///fixture/movie.mp4", "", "Movie"))
        for (selection in PlaybackEngineSelection.entries) {
            assertNull(handoffPlaybackBlockReason(selection, items, 0), selection.name)
        }
    }
}
