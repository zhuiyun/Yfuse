package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSyncStoreTest {
    @Test
    fun a_missing_entity_rebase_survives_restart_without_losing_the_local_mutation() {
        val settings = MapSettings()
        val store = PlaybackSyncStore(settings) { 1_000L }
        store.markManual("tmdb:1", watched = true)
        val pending = store.pending().single()
        store.markUploaded("tmdb:1", emptyList(), entityKey = "entity", mutationId = pending.mutationId, cursor = 7L)
        store.markManual("tmdb:1", watched = false)
        val rejected = store.pending().single()

        assertTrue(store.resetMissingRemoteCursor(rejected, "entity", 7L))

        val restored = PlaybackSyncStore(settings).pending().single()
        assertEquals(rejected.document, restored.document)
        assertEquals(rejected.mutationId, restored.mutationId)
        assertTrue(restored.dirty)
        assertEquals(null, restored.remoteCursors["entity"])
    }

    @Test
    fun a_late_missing_response_cannot_reset_a_newer_cloud_cursor_or_local_mutation() {
        var now = 1_000L
        val store = PlaybackSyncStore(MapSettings()) { now }
        store.markManual("tmdb:1", watched = true)
        val original = store.pending().single()
        store.markUploaded("tmdb:1", emptyList(), entityKey = "entity", mutationId = original.mutationId, cursor = 7L)
        now++
        store.markManual("tmdb:1", watched = false)
        val rejected = store.pending().single()
        store.applyRemote(rejected.document, "entity", 9L)
        assertFalse(store.resetMissingRemoteCursor(rejected, "entity", 7L))
        assertEquals(9L, store.pending().single().remoteCursors["entity"])
        val olderMutation = store.pending().single()
        now++
        store.markManual("tmdb:1", watched = true)
        assertFalse(store.resetMissingRemoteCursor(olderMutation, "entity", 9L))
        assertEquals(9L, store.pending().single().remoteCursors["entity"])
        assertTrue(
            store
                .pending()
                .single()
                .document.state.played,
        )
    }

    @Test
    fun serverBatchPersistsOncePreservesLocalMutationsAndRejectsChangedScope() {
        val backing = MapSettings()
        var writes = 0
        val settings =
            object : Settings by backing {
                override fun putString(
                    key: String,
                    value: String,
                ) {
                    writes++
                    backing.putString(key, value)
                }
            }
        val store = PlaybackSyncStore(settings) { 1_000L }
        val inputs = (1..156).map { PlaybackSyncStore.ServerProgressInput("movie-$it", it * 1_000L, false) }
        writes = 0
        store.absorbServerProgressBatch("server-a", inputs, store.scopeToken)
        assertEquals(1, writes)
        assertTrue(store.pending().isEmpty())
        assertEquals(156_000L, PlaybackSyncStore(backing).stateForServerItem("server-a", "movie-156")?.positionMs)
        store.absorbServerProgressBatch("server-a", inputs, store.scopeToken)
        assertEquals(1, writes)
        store.absorbServerProgressBatch("server-b", inputs, "obsolete-scope")
        assertEquals(null, store.stateForServerItem("server-b", "movie-1"))
        assertEquals(1, writes)
        store.markManual("emby:movie-1", watched = true, serverId = "server-a", serverItemId = "movie-1")
        val afterLocalWrite = writes
        store.absorbServerProgressBatch("server-a", inputs, store.scopeToken)
        assertEquals(afterLocalWrite, writes)
        assertTrue(store.stateForServerItem("server-a", "movie-1")?.played == true)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun serverCooldownCoversEveryQueuedTitleAndSurvivesRestart() {
        val settings = MapSettings()
        val store = PlaybackSyncStore(settings) { 1_000L }
        repeat(40) { index ->
            store.enqueueServerApply(
                PlaybackSyncDocument(state = PlaybackStateRecord(mediaKey = "tmdb:$index", deviceId = "remote")),
                listOf("blocked", "healthy"),
            )
        }

        store.deferServerAppliesForServer("blocked", 31_000L)

        val restored = PlaybackSyncStore(settings) { 2_000L }
        repeat(40) {
            val task = restored.pendingServerApplies(2_000L, limit = 1).single()
            assertEquals(listOf("healthy"), task.readyServerIds(2_000L))
            restored.markServerApplySucceeded(task.id, "healthy")
        }
        assertEquals(40, restored.serverApplyCount())
        assertTrue(restored.pendingServerApplies(30_999L).isEmpty())
        assertEquals(31_000L, restored.nextServerApplyAtEpochMs())
        assertEquals(
            listOf("blocked"),
            restored.pendingServerApplies(31_000L, limit = 1).single().readyServerIds(31_000L),
        )
    }

    @Test
    fun replacingProgressAfterRestartKeepsTheNewPositionAndTheServerCooldown() {
        val settings = MapSettings()
        val store = PlaybackSyncStore(settings) { 1_000L }
        val original =
            PlaybackSyncDocument(
                state = PlaybackStateRecord(mediaKey = "tmdb:1", deviceId = "remote", positionMs = 10_000L),
            )
        store.enqueueServerApply(original, listOf("blocked", "healthy"))
        store.deferServerAppliesForServer("blocked", 31_000L)
        val restored = PlaybackSyncStore(settings) { 2_000L }

        restored.enqueueServerApply(
            original.copy(state = original.state.copy(positionMs = 20_000L)),
            listOf("blocked", "healthy"),
        )

        assertEquals(1, restored.serverApplyCount())
        val task = restored.pendingServerApplies(2_000L).single()
        assertEquals(20_000L, task.document.state.positionMs)
        assertEquals(listOf("healthy"), task.readyServerIds(2_000L))
        restored.markServerApplySucceeded(task.id, "healthy")
        assertTrue(restored.pendingServerApplies(30_999L).isEmpty())
        assertEquals(31_000L, restored.nextServerApplyAtEpochMs())
    }

    @Test
    fun aDifferentTitleInheritsThePersistedServerCooldownWithoutDelayingHealthyServers() {
        val settings = MapSettings()
        val store = PlaybackSyncStore(settings) { 1_000L }
        store.enqueueServerApply(
            PlaybackSyncDocument(state = PlaybackStateRecord(mediaKey = "tmdb:1", deviceId = "remote")),
            listOf("blocked"),
        )
        store.deferServerAppliesForServer("blocked", 31_000L)
        val restored = PlaybackSyncStore(settings) { 2_000L }

        restored.enqueueServerApply(
            PlaybackSyncDocument(state = PlaybackStateRecord(mediaKey = "tmdb:2", deviceId = "remote")),
            listOf("blocked", "healthy"),
        )

        val task = restored.pendingServerApplies(2_000L).single()
        assertEquals("tmdb:2", task.document.state.mediaKey)
        assertEquals(listOf("healthy"), task.readyServerIds(2_000L))
        assertEquals(31_000L, task.deferredUntilByServerId["blocked"])
    }

    @Test
    fun cooledServerStaysDurableWhileHealthyTargetsAdvance() {
        val settings = MapSettings()
        val store = PlaybackSyncStore(settings) { 1_000L }
        val document = PlaybackSyncDocument(state = PlaybackStateRecord(mediaKey = "tmdb:1", deviceId = "remote"))
        store.enqueueServerApply(document, listOf("blocked", "healthy"))
        val task = store.pendingServerApplies(1_000L).single()
        store.deferServerApplyTarget(task.id, "blocked", 31_000L)
        assertEquals(listOf("healthy"), store.pendingServerApplies(1_000L).single().readyServerIds(1_000L))
        store.markServerApplySucceeded(task.id, "healthy")
        assertTrue(store.pendingServerApplies(1_000L).isEmpty())
        assertEquals(1, store.serverApplyCount())
        assertEquals(31_000L, store.nextServerApplyAtEpochMs())
        val restored = PlaybackSyncStore(settings) { 2_000L }
        assertTrue(restored.pendingServerApplies(30_999L).isEmpty())
        assertEquals(listOf("blocked"), restored.pendingServerApplies(31_000L).single().readyServerIds(31_000L))
    }

    @Test
    fun startupServerProgressSeedsOnlyMissingItemsWithoutCreatingUpload() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }

        assertTrue(
            store.seedServerProgressIfAbsent(
                serverId = "server-a",
                itemId = "movie-1",
                positionMs = 25_000L,
                played = false,
            ),
        )

        val seeded = requireNotNull(store.stateForServerItem("server-a", "movie-1"))
        assertEquals(25_000L, seeded.positionMs)
        assertFalse(seeded.played)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun startupServerProgressNeverOverwritesExistingLocalState() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }
        store.updatePlayback(
            mediaKey = "emby:movie-1",
            aliases = emptyList(),
            positionMs = 70_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "local",
            serverId = "server-a",
            serverItemId = "movie-1",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )

        assertFalse(
            store.seedServerProgressIfAbsent(
                serverId = "server-a",
                itemId = "movie-1",
                positionMs = 10_000L,
                played = true,
            ),
        )
        val retained = requireNotNull(store.stateForServerItem("server-a", "movie-1"))
        assertEquals(70_000L, retained.positionMs)
        assertFalse(retained.played)
    }

    @Test
    fun serverProgressReplacesACleanLocalRecordButNeverAnUnsentOne() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }
        store.seedServerProgressIfAbsent("server-a", "movie-1", positionMs = 25_000L, played = false)

        // Watched further on another device: the clean record follows the server.
        assertTrue(store.absorbServerProgress("server-a", "movie-1", positionMs = 80_000L, played = false))
        assertEquals(80_000L, store.stateForServerItem("server-a", "movie-1")?.positionMs)
        assertTrue(store.pending().isEmpty())

        // Same answer again: nothing to do.
        assertFalse(store.absorbServerProgress("server-a", "movie-1", positionMs = 80_000L, played = false))

        // A local playback the server has not received yet keeps its position.
        store.updatePlayback(
            mediaKey = "emby:movie-1",
            aliases = emptyList(),
            positionMs = 95_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "local",
            serverId = "server-a",
            serverItemId = "movie-1",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )
        assertFalse(store.absorbServerProgress("server-a", "movie-1", positionMs = 10_000L, played = true))
        assertEquals(95_000L, store.stateForServerItem("server-a", "movie-1")?.positionMs)
    }

    @Test
    fun serverLocalEmbyIdsNeverMergeAcrossServers() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }

        fun update(
            serverId: String,
            positionMs: Long,
        ) = store.updatePlayback(
            mediaKey = "emby:42",
            aliases = emptyList(),
            positionMs = positionMs,
            durationMs = 100_000L,
            played = false,
            sessionId = serverId,
            serverId = serverId,
            serverItemId = "42",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )

        update("server-a", 10_000L)
        update("server-b", 80_000L)

        assertEquals(
            10_000L,
            store
                .find("emby:42", serverId = "server-a")
                ?.document
                ?.state
                ?.positionMs,
        )
        assertEquals(
            80_000L,
            store
                .find("emby:42", serverId = "server-b")
                ?.document
                ?.state
                ?.positionMs,
        )
        assertEquals(null, store.find("emby:42"))
        assertEquals(2, store.pending().size)
    }

    @Test
    fun invalidLocalDocumentsResetCloudCursorForFullRecovery() {
        val settings =
            MapSettings().apply {
                putString("playback.cross_platform.documents.v1", "{broken-json")
                putLong("playback.cross_platform.cursor.v1", 91L)
            }

        val store = PlaybackSyncStore(settings) { 1_000L }

        assertTrue(store.pending().isEmpty())
        assertEquals(0L, store.cursor())
    }

    @Test
    fun serverApplyQueueSurvivesRestartAndAdvancesOneServerAtATime() {
        val settings = MapSettings()
        val document =
            PlaybackSyncDocument(
                state =
                    PlaybackStateRecord(
                        mediaKey = "tmdb:1",
                        deviceId = "remote-device",
                    ),
            )
        val first = PlaybackSyncStore(settings) { 1_000L }
        first.enqueueServerApply(document, listOf("server-a", "server-b"))

        val restored = PlaybackSyncStore(settings) { 2_000L }
        val task = restored.pendingServerApplies(nowEpochMs = 2_000L).single()
        assertEquals(listOf("server-a", "server-b"), task.remainingServerIds)

        restored.markServerApplySucceeded(task.id, "server-a")
        assertEquals(listOf("server-b"), restored.pendingServerApplies(2_000L).single().remainingServerIds)
        restored.markServerApplySucceeded(task.id, "server-b")
        assertEquals(0, restored.serverApplyCount())
    }

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
    fun uploadAcknowledgementDoesNotAdvancePullCheckpoint() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }
        store.updateCursor(7L)
        val pending =
            store.updatePlayback(
                mediaKey = "tmdb:1",
                aliases = emptyList(),
                positionMs = 20_000L,
                durationMs = 100_000L,
                played = false,
                sessionId = "phone",
                serverId = "server-a",
                serverItemId = "item-a",
                mutationKind = PlaybackMutationKind.AutoProgress,
                trigger = PlaybackSyncTrigger.Periodic,
            )

        store.markUploaded(
            mediaKey = "tmdb:1",
            aliases = emptyList(),
            entityKey = "local-entity",
            mutationId = pending.mutationId,
            cursor = 9L,
        )

        assertEquals(7L, store.cursor())
        assertEquals(9L, store.find("tmdb:1")?.remoteCursors?.get("local-entity"))
        assertTrue(store.pending().isEmpty())
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
