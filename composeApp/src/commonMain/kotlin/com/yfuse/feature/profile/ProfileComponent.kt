package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.Store
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
import com.yfuse.core.handoff.HandoffUiState
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.util.clearImageCache
import com.yfuse.core.util.clearVideoCache
import com.yfuse.feature.handoff.DeviceHandoffIntent
import com.yfuse.feature.handoff.DeviceHandoffStoreFactory
import com.yfuse.feature.personal.PersonalCenterIntent
import com.yfuse.feature.personal.PersonalCenterState
import com.yfuse.feature.personal.PersonalCenterStoreFactory
import com.yfuse.feature.personal.PersonalSync
import com.yfuse.core.util.imageCacheUsageBytes as currentImageCacheUsageBytes
import com.yfuse.core.util.videoCacheUsageBytes as currentVideoCacheUsageBytes

class ProfileComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val registry: ServerRegistry,
    val themePreferences: ThemePreferences,
    /** Re-opens the player on the current 一起看 room; see `RootComponent.enterWatchRoom`. */
    val onEnterWatchRoom: () -> Unit,
    /** Switches to the 服务器 tab, which owns the list this page used to embed. */
    val onOpenServers: () -> Unit,
    val dependencies: AppDependencies,
    val onOpenPersonalMedia: (com.yfuse.core.personal.PersonalMediaRef) -> Unit = {},
) : ComponentContext by componentContext {
    val repository by lazy {
        org.koin.core.context.GlobalContext
            .get()
            .get<com.yfuse.core.data.EmbyRepository>()
    }
    val personal by lazy {
        org.koin.core.context.GlobalContext
            .get()
            .get<com.yfuse.core.personal.PersonalLibraryRepository>()
    }
    val playbackSync by lazy {
        org.koin.core.context.GlobalContext
            .get()
            .get<com.yfuse.core.sync.playback.PlaybackSyncManager>()
    }
    val handoff by lazy {
        org.koin.core.context.GlobalContext
            .get()
            .get<com.yfuse.core.handoff.HandoffController>()
    }
    val trakt by lazy {
        org.koin.core.context.GlobalContext
            .get()
            .get<com.yfuse.core.trakt.TraktRepository>()
    }

    fun familyServers() =
        if (personal.policy.value.canManageServers) registry.allDataForSync().servers else registry.data.value.servers

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

    /** Clear the shared image cache; offline video files and library metadata are untouched. */
    suspend fun onClearCache() = clearImageCache()

    suspend fun imageCacheUsageBytes(): Long = currentImageCacheUsageBytes()

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

    // Last among the properties: a stack restored after process death builds its pages here, and
    // a page may read anything declared above.
    private val pageStack = ProfilePages(this, ::pageChild)

    /** 我的's pages, the settings root at the bottom; [ProfileScreen] draws them. */
    val pages: Value<ChildStack<ProfilePage, Child>> = pageStack.stack

    /** What a page of [pages] is. */
    sealed interface Child {
        /** A page drawn from this component's own preferences and state; [page] says which. */
        data class Settings(
            val page: ProfilePage,
        ) : Child

        /** 我的内容, 家庭资料 or 同步状态与恢复, one store for the three. */
        class Personal(
            val store: Store<PersonalCenterIntent, PersonalCenterState, Nothing>,
        ) : Child

        /** 设备接力. */
        class Handoff(
            val store: Store<DeviceHandoffIntent, HandoffUiState, Nothing>,
        ) : Child
    }

    /** A page's store is made as the page opens and disposed as it closes, as its state was before. */
    private fun pageChild(
        page: ProfilePage,
        context: ComponentContext,
    ): Child =
        when (page) {
            ProfilePage.Personal, ProfilePage.Family, ProfilePage.Sync ->
                Child.Personal(
                    PersonalCenterStoreFactory(
                        storeFactory = storeFactory,
                        personal = personal,
                        sync = PersonalSync(account, playbackSync, dependencies.serverSyncManager),
                        repo = repository,
                    ).create()
                        .disposedWith(context),
                )
            ProfilePage.Handoff ->
                Child.Handoff(DeviceHandoffStoreFactory(storeFactory, handoff).create().disposedWith(context))
            else -> Child.Settings(page)
        }

    private fun <S : Store<*, *, *>> S.disposedWith(context: ComponentContext): S =
        also { store -> context.lifecycle.doOnDestroy(store::dispose) }

    /** Opens [page] over whatever is showing; see [ProfilePages.open]. */
    fun openPage(page: ProfilePage) = pageStack.open(page)

    /** Back from the page in front — its back button, the gesture, system back. */
    fun closePage() = pageStack.close()

    /** 下载与离线库 from outside 我的 — a download's notification, the activity capsule. */
    fun openDownloads() = pageStack.openDownloads()

    /**
     * 一起看 has become unavailable — signed out, or the session lapsed: its page closes if it is in
     * front. [ProfileScreen] calls it as it sees that, arriving on screen included, so a page left
     * open while that happened elsewhere closes as 我的 comes back into view.
     */
    fun closeWatchTogetherPage() = pageStack.closeWatchTogether()

    init {
        lifecycle.doOnDestroy {
            store.dispose()
        }
    }
}
