package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStaleServerApplyRegressionTest {
    @Test
    fun newer_local_progress_invalidates_a_deferred_older_server_writeback_snapshot() {
        var now = 1_000L
        val store = PlaybackSyncStore(MapSettings()) { now }
        val remote =
            PlaybackSyncDocument(
                state =
                    PlaybackStateRecord(
                        mediaKey = "tmdb:1",
                        aliases = listOf("emby:42"),
                        positionMs = 600_000L,
                        durationMs = 3_600_000L,
                        lastPlayedAtEpochMs = now,
                        deviceId = "remote-device",
                        sessionId = "remote-session",
                        serverId = "server-a",
                        serverItemId = "42",
                        revision = 1L,
                    ),
            )
        val pulled = store.applyRemote(remote, entityKey = "cloud-title", cursor = 1L)
        store.enqueueServerApply(pulled.document, serverIds = listOf("server-b"))
        store.deferServerAppliesForServer("server-b", untilEpochMs = 10_000L)

        now = 2_000L
        val local =
            store.updatePlayback(
                mediaKey = "tmdb:1",
                aliases = listOf("emby:99"),
                positionMs = 1_800_000L,
                durationMs = 3_600_000L,
                played = false,
                sessionId = "local-session",
                serverId = "server-b",
                serverItemId = "99",
                mutationKind = PlaybackMutationKind.AutoProgress,
                trigger = PlaybackSyncTrigger.Stop,
            )
        assertEquals(1_800_000L, local.document.state.positionMs)

        now = 10_000L
        val replacement = store.pendingServerApplies(now).single()
        assertEquals(1_800_000L, replacement.document.state.positionMs)
        assertEquals(true, replacement.forceApply, "Reconcile a delayed old write even when B is now the origin")
        store.enqueueServerApply(replacement.document, listOf("server-b"))
        assertEquals(true, store.pendingServerApplies(now).single().forceApply)
        val staleTasks = store.pendingServerApplies(now).filter { it.document.state == remote.state }
        assertEquals(
            0,
            staleTasks.size,
            "After local playback advances to 30 minutes, the older 10-minute snapshot must no longer be deliverable",
        )
    }
}
