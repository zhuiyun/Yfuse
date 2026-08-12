package com.yfuse.core.sync

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CloudSyncSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun server_sync_settings_capture_apply_and_persist_without_runtime_state() {
        val source = Fixture(syncSettings = settingsWithPending("source-pending"))
        source.serverSync.setAutoSync(false)
        source.serverSync.setMetadata(false)
        source.serverSync.setProgress(false)
        source.serverSync.setArtwork(false)
        source.serverSync.setFavorites(false)
        assertEquals(1, source.serverSync.state.value.pendingCount)

        val snapshot = source.capture()

        assertEquals(
            CloudServerSyncSettings(
                autoSync = false,
                syncMetadata = false,
                syncProgress = false,
                syncArtwork = false,
                syncFavorites = false,
            ),
            snapshot.serverSync,
        )
        val encoded = json.encodeToString(CloudSyncSnapshotV1.serializer(), snapshot)
        assertFalse(encoded.contains("pending", ignoreCase = true))
        assertFalse(encoded.contains("conflict", ignoreCase = true))

        val targetSettings = settingsWithPending("target-pending")
        val target = Fixture(syncSettings = targetSettings)
        val targetPending = target.serverSync.state.value.pendingOperations

        target.apply(snapshot).getOrThrow()

        assertFalse(target.serverSync.autoSync.value)
        assertFalse(target.serverSync.syncMetadata.value)
        assertFalse(target.serverSync.syncProgress.value)
        assertFalse(target.serverSync.syncArtwork.value)
        assertFalse(target.serverSync.syncFavorites.value)
        assertEquals(targetPending, target.serverSync.state.value.pendingOperations)

        val restored = ServerSyncManager(
            repo = testRepo { json("{}") },
            registry = target.registry,
            settings = targetSettings,
        )
        assertFalse(restored.autoSync.value)
        assertFalse(restored.syncMetadata.value)
        assertFalse(restored.syncProgress.value)
        assertFalse(restored.syncArtwork.value)
        assertFalse(restored.syncFavorites.value)
        assertEquals(targetPending, restored.state.value.pendingOperations)
    }

    @Test
    fun v1_snapshot_without_server_sync_settings_keeps_legacy_true_defaults() {
        val decoded = json.decodeFromString(
            CloudSyncSnapshotV1.serializer(),
            """{"schemaVersion":1}""",
        )

        assertEquals(CloudServerSyncSettings(), decoded.serverSync)
    }

    private fun settingsWithPending(itemId: String): MapSettings = MapSettings().apply {
        val mutation = PendingSyncMutation(
            serverId = "server-a",
            itemId = itemId,
            title = "仅本机队列",
            kind = SyncMutationKind.Favorite,
            desired = true,
            baseValue = false,
            createdAtEpochMs = 1L,
        )
        putString(
            "sync.pending.v1",
            json.encodeToString(
                ListSerializer(PendingSyncMutation.serializer()),
                listOf(mutation),
            ),
        )
    }
}

private class Fixture(
    val syncSettings: MapSettings = MapSettings(),
) {
    val registry = ServerRegistry(MapSettings(), TestSecureStore())
    val theme = ThemePreferences(MapSettings())
    val watch = WatchTogetherPreferences(MapSettings())
    val danmaku = DanmakuPreferences(MapSettings())
    val skip = SkipSegmentPreferences(MapSettings())
    val serverSync = ServerSyncManager(
        repo = testRepo { json("{}") },
        registry = registry,
        settings = syncSettings,
    )

    fun capture(): CloudSyncSnapshotV1 = captureCloudSyncSnapshot(
        registry = registry,
        theme = theme,
        watch = watch,
        danmaku = danmaku,
        skip = skip,
        serverSync = serverSync,
    )

    fun apply(snapshot: CloudSyncSnapshotV1): Result<Unit> = applyCloudSyncSnapshot(
        snapshot = snapshot,
        registry = registry,
        theme = theme,
        watch = watch,
        danmaku = danmaku,
        skip = skip,
        serverSync = serverSync,
    )
}
