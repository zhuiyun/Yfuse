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

    @Test
    fun matchedAliasKeepsExistingCanonicalMediaKey() {
        val settings = MapSettings()
        var now = 1_000L
        val store = PlaybackSyncStore(settings) { now++ }
        store.updatePlayback(
            mediaKey = "tmdb:1",
            aliases = listOf("imdb:tt1"),
            positionMs = 10_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "phone",
            serverId = "server-a",
            serverItemId = "item-a",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )

        store.updatePlayback(
            mediaKey = "imdb:tt1",
            aliases = listOf("tmdb:1"),
            positionMs = 20_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "tv",
            serverId = "server-b",
            serverItemId = "item-b",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )

        val state = requireNotNull(store.find("imdb:tt1", listOf("tmdb:1"))).document.state
        assertEquals("tmdb:1", state.mediaKey)
        assertTrue("imdb:tt1" in state.aliases)
        assertEquals(20_000L, state.positionMs)
    }

    @Test
    fun equivalentAliasRemoteDoesNotCauseUploadPingPong() {
        val settings = MapSettings()
        val store = PlaybackSyncStore(settings) { 1_000L }
        val local =
            store.updatePlayback(
                mediaKey = "tmdb:1",
                aliases = listOf("imdb:tt1"),
                positionMs = 20_000L,
                durationMs = 100_000L,
                played = false,
                sessionId = "session",
                serverId = "server-a",
                serverItemId = "item-a",
                mutationKind = PlaybackMutationKind.AutoProgress,
                trigger = PlaybackSyncTrigger.Periodic,
            )
        store.markUploaded(
            mediaKey = "tmdb:1",
            aliases = listOf("imdb:tt1"),
            entityKey = "local-entity",
            mutationId = local.mutationId,
            cursor = 1L,
        )
        val remote =
            local.document.copy(
                state =
                    local.document.state.copy(
                        mediaKey = "imdb:tt1",
                        aliases = listOf("tmdb:1"),
                    ),
            )

        val applied = store.applyRemote(remote, entityKey = "remote-entity", cursor = 2L)

        assertFalse(applied.changedLocal)
        assertFalse(applied.needsUpload)
        assertTrue(store.pending().isEmpty())
        assertEquals("tmdb:1", applied.document.state.mediaKey)
    }

    @Test
    fun manualUnwatchedThenStartedCreatesFreshGeneration() {
        val settings = MapSettings()
        var now = 10_000L
        val store = PlaybackSyncStore(settings) { now++ }
        store.updatePlayback(
            mediaKey = "tmdb:1",
            aliases = emptyList(),
            positionMs = 70_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "old",
            serverId = "server-a",
            serverItemId = "item-a",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )
        val reset = store.markManual("tmdb:1", watched = false)
        val resetEpoch = reset.document.state.progressEpoch

        val started =
            store.updatePlayback(
                mediaKey = "tmdb:1",
                aliases = emptyList(),
                positionMs = 0L,
                durationMs = 100_000L,
                played = false,
                sessionId = "new",
                serverId = "server-a",
                serverItemId = "item-a",
                mutationKind = PlaybackMutationKind.AutoProgress,
                trigger = PlaybackSyncTrigger.Started,
            )

        assertEquals(resetEpoch + 1L, started.document.state.progressEpoch)
        assertEquals(PlaybackMutationKind.AutoProgress, started.document.state.mutationKind)
    }

    @Test
    fun explicitRestartKeepsItsGenerationWhenPlayerStarts() {
        val settings = MapSettings()
        var now = 20_000L
        val store = PlaybackSyncStore(settings) { now++ }
        store.updatePlayback(
            mediaKey = "tmdb:1",
            aliases = emptyList(),
            positionMs = 70_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "old",
            serverId = "server-a",
            serverItemId = "item-a",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )
        val restart = store.markRestarted("tmdb:1")
        val restartEpoch = restart.document.state.progressEpoch

        val started =
            store.updatePlayback(
                mediaKey = "tmdb:1",
                aliases = emptyList(),
                positionMs = 0L,
                durationMs = 100_000L,
                played = false,
                sessionId = "new",
                serverId = "server-a",
                serverItemId = "item-a",
                mutationKind = PlaybackMutationKind.AutoProgress,
                trigger = PlaybackSyncTrigger.Started,
            )

        assertEquals(restartEpoch, started.document.state.progressEpoch)
    }
}
