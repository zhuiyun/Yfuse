package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackBackwardSeekRegressionTest {
    @Test
    fun a_newer_backward_seek_from_the_same_session_replaces_a_cached_remote_position() {
        var now = 1_000L
        val phone = PlaybackSyncStore(MapSettings()) { now }
        val television = PlaybackSyncStore(MapSettings()) { now }

        val beforeSeek = phone.record(1_200_000L, PlaybackSyncTrigger.Started)
        television.applyRemote(beforeSeek.document, entityKey = "cloud-title", cursor = 1L)

        now = 2_000L
        phone.record(600_000L, PlaybackSyncTrigger.Seek)
        now = 3_000L
        val afterSeek = phone.record(600_000L, PlaybackSyncTrigger.Stop)
        assertEquals(600_000L, afterSeek.document.state.positionMs)

        // A later cloud page carries the same phone session's deliberately lower final position.
        television.applyRemote(afterSeek.document, entityKey = "cloud-title", cursor = 2L)

        assertEquals(
            600_000L,
            television
                .find("tmdb:1")
                ?.document
                ?.state
                ?.positionMs,
            "The older 20-minute snapshot must not override the later 10-minute stop after a backward seek",
        )
    }

    private fun PlaybackSyncStore.record(
        positionMs: Long,
        trigger: PlaybackSyncTrigger,
    ) = updatePlayback(
        mediaKey = "tmdb:1",
        aliases = emptyList(),
        positionMs = positionMs,
        durationMs = 3_600_000L,
        played = false,
        sessionId = "phone-session",
        serverId = "server-a",
        serverItemId = "item-a",
        mutationKind = PlaybackMutationKind.AutoProgress,
        trigger = trigger,
    )
}
