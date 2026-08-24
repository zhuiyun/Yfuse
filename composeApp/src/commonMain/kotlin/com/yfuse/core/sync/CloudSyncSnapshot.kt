package com.yfuse.core.sync

import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuSyncSnapshot
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.SkipTimes
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.ServersData
import kotlinx.serialization.Serializable

/** Everything in this document is encrypted before it leaves the device. */
@Serializable
data class CloudSyncSnapshotV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val servers: ServersData = ServersData(),
    val appearance: CloudAppearanceSettings = CloudAppearanceSettings(),
    val watchProfile: CloudWatchProfile = CloudWatchProfile(),
    val danmaku: DanmakuSyncSnapshot = DanmakuSyncSnapshot(),
    val serverSync: CloudServerSyncSettings = CloudServerSyncSettings(),
    val skipMode: String = SkipMode.Button.name,
    val skipTimesBySeries: Map<String, SkipTimes> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class CloudAppearanceSettings(
    val themeMode: String = ThemeMode.Light.name,
    val autoNext: Boolean = true,
    val reduceTransparency: Boolean = false,
    val largeText: Boolean = false,
    val reduceMotion: Boolean = false,
    val splashAnimation: Boolean = true,
    val splashVariant: String = SplashAnimation.One.name,
)

@Serializable
data class CloudWatchProfile(
    val chatPreviewEnabled: Boolean = true,
    val chatDanmakuEnabled: Boolean = true,
)

/** Stable user choices only; queues, conflicts, status, and retry state remain device-local. */
@Serializable
data class CloudServerSyncSettings(
    val autoSync: Boolean = true,
    val syncMetadata: Boolean = true,
    val syncProgress: Boolean = true,
    val syncArtwork: Boolean = true,
    val syncFavorites: Boolean = true,
)

fun captureCloudSyncSnapshot(
    registry: ServerRegistry,
    theme: ThemePreferences,
    watch: WatchTogetherPreferences,
    danmaku: DanmakuPreferences,
    skip: SkipSegmentPreferences,
    serverSync: ServerSyncManager,
): CloudSyncSnapshotV1 =
    CloudSyncSnapshotV1(
        servers = registry.data.value,
        appearance =
            CloudAppearanceSettings(
                themeMode = theme.mode.value.name,
                autoNext = theme.autoNext.value,
                reduceTransparency = theme.reduceTransparency.value,
                largeText = theme.largeText.value,
                reduceMotion = theme.reduceMotion.value,
                splashAnimation = theme.splashAnimation.value,
                splashVariant = theme.splashVariant.value.name,
            ),
        watchProfile =
            CloudWatchProfile(
                chatPreviewEnabled = watch.chatPreviewEnabled.value,
                chatDanmakuEnabled = watch.chatDanmakuEnabled.value,
            ),
        danmaku = danmaku.snapshot(),
        serverSync =
            CloudServerSyncSettings(
                autoSync = serverSync.autoSync.value,
                syncMetadata = serverSync.syncMetadata.value,
                syncProgress = serverSync.syncProgress.value,
                syncArtwork = serverSync.syncArtwork.value,
                syncFavorites = serverSync.syncFavorites.value,
            ),
        skipMode = skip.skipMode.value.name,
        skipTimesBySeries = skip.bySeries.value,
    )

/** Applies a successfully authenticated and decrypted snapshot through typed preference APIs. */
fun applyCloudSyncSnapshot(
    snapshot: CloudSyncSnapshotV1,
    registry: ServerRegistry,
    theme: ThemePreferences,
    watch: WatchTogetherPreferences,
    danmaku: DanmakuPreferences,
    skip: SkipSegmentPreferences,
    serverSync: ServerSyncManager,
): Result<Unit> =
    runCatching {
        require(snapshot.schemaVersion == CloudSyncSnapshotV1.CURRENT_SCHEMA_VERSION) {
            "暂不支持这个同步数据版本"
        }
        require(snapshot.skipTimesBySeries.size <= 500) { "片头片尾同步数据过多" }
        require(snapshot.skipTimesBySeries.keys.all { it.isNotBlank() && it.length <= 512 }) {
            "片头片尾同步数据无效"
        }

        val mode = ThemeMode.entries.named(snapshot.appearance.themeMode, ThemeMode.Light)
        val splash =
            SplashAnimation.entries.named(
                snapshot.appearance.splashVariant,
                SplashAnimation.One,
            )
        val skipMode = SkipMode.entries.named(snapshot.skipMode, SkipMode.Button)
        val normalizedDanmaku = danmaku.validateSnapshot(snapshot.danmaku).getOrThrow()

        registry.replaceFromSync(snapshot.servers).getOrThrow()
        theme.setMode(mode)
        theme.setAutoNext(snapshot.appearance.autoNext)
        theme.setReduceTransparency(snapshot.appearance.reduceTransparency)
        theme.setLargeText(snapshot.appearance.largeText)
        theme.setReduceMotion(snapshot.appearance.reduceMotion)
        theme.setSplashAnimation(snapshot.appearance.splashAnimation)
        theme.setSplashVariant(splash)
        watch.setChatPreviewEnabled(snapshot.watchProfile.chatPreviewEnabled)
        watch.setChatDanmakuEnabled(snapshot.watchProfile.chatDanmakuEnabled)
        danmaku.applySnapshot(normalizedDanmaku).getOrThrow()
        serverSync.setMetadata(snapshot.serverSync.syncMetadata)
        serverSync.setProgress(snapshot.serverSync.syncProgress)
        serverSync.setArtwork(snapshot.serverSync.syncArtwork)
        serverSync.setFavorites(snapshot.serverSync.syncFavorites)
        serverSync.setAutoSync(snapshot.serverSync.autoSync)

        (skip.bySeries.value.keys - snapshot.skipTimesBySeries.keys).forEach(skip::clear)
        snapshot.skipTimesBySeries.forEach(skip::set)
        skip.setSkipMode(skipMode)
    }

private fun <T : Enum<T>> List<T>.named(
    name: String,
    fallback: T,
): T = firstOrNull { it.name == name } ?: fallback
