package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.util.clearImageCache
import com.yfuse.core.util.clearVideoCache
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.core.util.videoCacheUsageBytes as currentVideoCacheUsageBytes

class ProfileComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val registry: ServerRegistry,
    val themePreferences: ThemePreferences,
    /** Re-opens the player on the current 一起看 room; see `RootComponent.enterWatchRoom`. */
    val onEnterWatchRoom: () -> Unit,
    /** Switches to the 服务器 tab, which owns the list this page used to embed. */
    val onOpenServers: () -> Unit,
    val dependencies: AppDependencies,
) : ComponentContext by componentContext {
    val store = ProfileStoreFactory(storeFactory, registry).create()

    val offlineMedia: OfflineMediaManager = dependencies.offlineMediaManager
    val playbackPreferences: PlaybackPreferences = dependencies.playbackPreferences
    val userAgentPreferences: UserAgentPreferences = dependencies.userAgentPreferences
    val danmakuPreferences: DanmakuPreferences = dependencies.danmakuPreferences
    val skipSegmentPreferences: SkipSegmentPreferences = dependencies.skipSegmentPreferences
    val watchTogetherPreferences: WatchTogetherPreferences = dependencies.watchTogetherPreferences
    val watchTogether: WatchTogetherClient = dependencies.watchTogether
    val account: AccountRepository = dependencies.account
    val serverHealthMonitor = dependencies.serverHealthMonitor
    val tgtoMedia = dependencies.tgtoMedia
    val tgtoMediaPreferences = dependencies.tgtoMediaPreferences

    /** Clear the shared image cache; offline video files and library metadata are untouched. */
    suspend fun onClearCache() = clearImageCache()

    /** Clear transient playback data; offline files and the image cache are untouched. */
    suspend fun onClearVideoCache(): Long = clearVideoCache()

    suspend fun videoCacheUsageBytes(): Long = currentVideoCacheUsageBytes()

    fun exportServers(
        passphrase: CharArray,
        createdAtEpochSeconds: Long,
    ): Result<String> = registry.exportProtectedBackup(passphrase, createdAtEpochSeconds)

    fun importServers(
        payload: String,
        passphrase: CharArray,
        nowEpochSeconds: Long,
    ): Result<Int> = registry.importProtectedBackup(payload, passphrase, nowEpochSeconds)

    fun exportRelayServers(createdAtEpochSeconds: Long) = registry.exportRelayBackup(createdAtEpochSeconds)

    fun inspectRelayServers(payload: String) = registry.inspectRelayBackup(payload)

    fun isRelayServers(payload: String): Boolean = registry.isRelayBackup(payload)

    fun importRelayServers(
        payload: String,
        transferSecret: ByteArray,
        nowEpochSeconds: Long,
    ): Result<Int> = registry.importRelayBackup(payload, transferSecret, nowEpochSeconds)

    init {
        lifecycle.doOnDestroy {
            store.dispose()
        }
    }
}

