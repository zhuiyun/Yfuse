package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.data.PlaybackRecoverySnapshot
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.util.clearImageCache
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.app.AppDependencies

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
    val syncManager: ServerSyncManager = dependencies.serverSyncManager
    val playbackRecovery: PlaybackRecoveryStore = dependencies.playbackRecovery
    val playbackPreferences: PlaybackPreferences = dependencies.playbackPreferences
    val userAgentPreferences: UserAgentPreferences = dependencies.userAgentPreferences
    val danmakuPreferences: DanmakuPreferences = dependencies.danmakuPreferences
    val skipSegmentPreferences: SkipSegmentPreferences = dependencies.skipSegmentPreferences
    val watchTogetherPreferences: WatchTogetherPreferences = dependencies.watchTogetherPreferences
    val watchTogether: WatchTogetherClient = dependencies.watchTogether
    val account: AccountRepository = dependencies.account
    val serverHealthMonitor = dependencies.serverHealthMonitor

    /** Clear the shared image cache; offline video files and library metadata are untouched. */
    suspend fun onClearCache() = clearImageCache()

    fun exportServers(
        passphrase: CharArray,
        createdAtEpochSeconds: Long,
    ): Result<String> = registry.exportProtectedBackup(passphrase, createdAtEpochSeconds)

    fun importServers(
        payload: String,
        passphrase: CharArray,
        nowEpochSeconds: Long,
    ): Result<Int> = registry.importProtectedBackup(payload, passphrase, nowEpochSeconds)

    fun recoveryItem(snapshot: PlaybackRecoverySnapshot): PlayerMediaItem? {
        val server = snapshot.serverId?.let(registry::serverById) ?: registry.defaultServer
        server ?: return null
        val urls = EmbyStream.streamUrls(server.baseUrl, snapshot.itemId, server.accessToken)
        return PlayerMediaItem(
            id = snapshot.itemId,
            url = urls.direct,
            transcodeUrl = urls.transcode,
            fallbackTranscodeUrl = urls.progressiveTranscode,
            playSessionId = urls.playSessionId,
            title = snapshot.title,
            serverId = server.id,
        )
    }

    init {
        lifecycle.doOnDestroy {
            store.dispose()
        }
    }
}
