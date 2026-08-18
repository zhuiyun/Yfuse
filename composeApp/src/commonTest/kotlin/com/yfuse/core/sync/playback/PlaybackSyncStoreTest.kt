package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSyncStoreTest {
    @Test
    fun sameAccountKeepsOfflineMutationsButDifferentAccountResetsPartition() {
        val settings = MapSettings()
        var now = 1_000L
        val store = PlaybackSyncStore(settings) { now++ }
        store.updatePlayback(
            mediaKey = "tmdb:1",
            aliases = listOf("imdb:tt1"),
            positionMs = 40_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "session",
            serverId = "server",
            serverItemId = "item",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )
        store.updateCursor(42L)

        assertFalse(store.bindAccount("user-a"))
        assertEquals(1, store.pending().size)
        assertEquals(42L, store.cursor())
        assertFalse(store.bindAccount("user-a"))
        assertEquals(1, store.pending().size)

        assertTrue(store.bindAccount("user-b"))
        assertTrue(store.pending().isEmpty())
        assertEquals(0L, store.cursor())
        assertEquals(null, store.find("tmdb:1", listOf("imdb:tt1")))
    }
}
