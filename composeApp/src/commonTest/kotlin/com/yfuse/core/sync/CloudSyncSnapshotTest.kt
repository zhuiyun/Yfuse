package com.yfuse.core.sync

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.DanmakuBinding
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.data.DanmakuSyncSnapshot
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.SkipTimes
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudSyncSnapshotTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

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

        val restored =
            ServerSyncManager(
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
    fun calendar_follows_and_reminders_round_trip_in_encrypted_snapshot() {
        val source = Fixture()
        source.follows.follow(
            FollowedSeries(
                tmdbId = 1399,
                title = "测试剧",
                reminderMode = CalendarReminderMode.BeforeAndAtBroadcast,
                remindBeforeMinutes = 60,
            ),
        )

        val snapshot = source.capture()
        val target = Fixture()
        target.apply(snapshot).getOrThrow()

        assertEquals(snapshot.calendarFollows, target.follows.followed.value)
        assertEquals(
            60,
            target.follows.followed.value
                .single()
                .remindBeforeMinutes,
        )
    }

    @Test
    fun v1_snapshot_without_server_sync_settings_keeps_legacy_true_defaults() {
        val decoded =
            json.decodeFromString(
                CloudSyncSnapshotV1.serializer(),
                """{"schemaVersion":1}""",
            )

        assertEquals(CloudServerSyncSettings(), decoded.serverSync)
    }

    @Test
    fun custom_user_agent_and_complete_danmaku_configuration_round_trip() {
        val source = Fixture()
        source.userAgent.setUserAgent("Yfuse-TV/2.0")
        val danmaku =
            DanmakuSyncSnapshot(
                sources = listOf(DanmakuSource("source-a", "主源", "https://danmaku.example.com")),
                activeSourceId = "source-a",
                bindings =
                    mapOf(
                        "series:1:2" to DanmakuBinding("source-a", "episode-2", "第 2 集"),
                    ),
                enabled = false,
                displayArea = DanmakuDisplayArea.Full,
                fontSize = DanmakuFontSize.Large,
                speed = DanmakuSpeed.Fast,
                opacity = DanmakuOpacity.High,
                mergeDuplicates = false,
                blockedWords = listOf("剧透", "广告"),
            )
        source.danmaku.applySnapshot(danmaku).getOrThrow()

        val snapshot = source.capture()
        assertEquals("Yfuse-TV/2.0", snapshot.network.customUserAgent)
        assertEquals(danmaku, snapshot.danmaku)

        val target = Fixture()
        target.userAgent.setUserAgent("Old-UA")
        target.apply(snapshot).getOrThrow()

        assertEquals("Yfuse-TV/2.0", target.userAgent.customValue.value)
        assertEquals(danmaku, target.danmaku.snapshot())
    }

    @Test
    fun legacy_v1_snapshot_defaults_to_stock_user_agent() {
        val snapshot =
            json.decodeFromString(
                CloudSyncSnapshotV1.serializer(),
                """{"schemaVersion":1}""",
            )
        val target = Fixture()
        target.userAgent.setUserAgent("Old-UA")

        target.apply(snapshot).getOrThrow()

        assertEquals("", target.userAgent.customValue.value)
        assertEquals(CloudNetworkSettings(), snapshot.network)
    }

    @Test
    fun legacy_snapshot_without_skip_domain_does_not_clear_local_boundaries() {
        val snapshot =
            json.decodeFromString(
                CloudSyncSnapshotV1.serializer(),
                """{"schemaVersion":1}""",
            )
        val target = Fixture()
        target.skip.set("series", SkipTimes(introEndSeconds = 90L))

        target.apply(snapshot).getOrThrow()

        assertEquals(90L, target.skip.timesFor("series")?.introEndSeconds)
    }

    @Test
    fun explicit_empty_skip_domain_still_clears_boundaries() {
        val target = Fixture()
        target.skip.set("series", SkipTimes(introEndSeconds = 90L))

        target.apply(CloudSyncSnapshotV1(skipTimesBySeries = emptyMap())).getOrThrow()

        assertTrue(
            target.skip.bySeries.value
                .isEmpty(),
        )
    }

    @Test
    fun invalid_cloud_user_agent_is_rejected_before_local_settings_change() {
        val target = Fixture()
        target.userAgent.setUserAgent("Local-UA")

        val result =
            target.apply(
                CloudSyncSnapshotV1(
                    network = CloudNetworkSettings(customUserAgent = "bad\r\nHeader: injected"),
                ),
            )

        assertFalse(result.isSuccess)
        assertEquals("Local-UA", target.userAgent.customValue.value)
    }

    private fun settingsWithPending(itemId: String): MapSettings =
        MapSettings().apply {
            val mutation =
                PendingSyncMutation(
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
    val userAgent = UserAgentPreferences(MapSettings())
    val watch = WatchTogetherPreferences(MapSettings())
    val danmaku = DanmakuPreferences(MapSettings())
    val skip = SkipSegmentPreferences(MapSettings())
    val follows = CalendarFollowStore(MapSettings())
    val serverSync =
        ServerSyncManager(
            repo = testRepo { json("{}") },
            registry = registry,
            settings = syncSettings,
        )

    fun capture(): CloudSyncSnapshotV1 =
        captureCloudSyncSnapshot(
            registry = registry,
            theme = theme,
            userAgent = userAgent,
            watch = watch,
            danmaku = danmaku,
            skip = skip,
            serverSync = serverSync,
            calendarFollows = follows,
        )

    fun apply(snapshot: CloudSyncSnapshotV1): Result<Unit> =
        applyCloudSyncSnapshot(
            snapshot = snapshot,
            registry = registry,
            theme = theme,
            userAgent = userAgent,
            watch = watch,
            danmaku = danmaku,
            skip = skip,
            serverSync = serverSync,
            calendarFollows = follows,
        )
}
