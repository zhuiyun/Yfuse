package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackSessionRetryTest {
    @Test
    fun relogin_releases_only_that_servers_durable_queue() {
        val settings = MapSettings()
        val store = PlaybackSyncStore(settings) { 1_000L }
        store.enqueueServerApply(
            PlaybackSyncDocument(state = PlaybackStateRecord(mediaKey = "tmdb:1", deviceId = "remote")),
            listOf("relogged", "blocked"),
        )
        store.deferServerAppliesForServer("relogged", 31_000L)
        store.deferServerAppliesForServer("blocked", 31_000L)
        assertTrue(store.pendingServerApplies(1_000L).isEmpty())
        store.resumeServerAppliesForServer("relogged")
        val restored = PlaybackSyncStore(settings) { 2_000L }
        val task = restored.pendingServerApplies(2_000L).single()
        assertEquals(listOf("relogged"), task.readyServerIds(2_000L))
        assertEquals(31_000L, task.deferredUntilByServerId["blocked"])
    }
}
