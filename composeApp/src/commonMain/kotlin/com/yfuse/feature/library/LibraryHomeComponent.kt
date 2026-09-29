package com.yfuse.feature.library

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.watchMatchKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.GlobalContext

class LibraryHomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    val onSeeAll: (libraryId: String, title: String) -> Unit,
    val onOpenItem: (itemId: String) -> Unit,
    val onPlayItem: (itemId: String) -> Unit,
    /**
     * 播放记录's tap and its 从头播放: straight into the player, from where the title was left or from
     * the start. Without a route of its own it goes through 详情's autoplay, as the hero's 播放 does.
     */
    val onResumeItem: (item: MediaItem, fromStart: Boolean) -> Unit = { item, _ -> onPlayItem(item.id) },
    val onOpenUnified: () -> Unit = {},
    /** No server to show: go and add one. */
    val onAddServer: () -> Unit = {},
    /** The current server refused its saved session: sign in to it again. */
    val onReauthenticate: (SavedServer) -> Unit = {},
    /** Per-server reachability — what tells a lapsed sign-in apart from any other failure. */
    val serverHealth: StateFlow<Map<String, ServerHealth>> = MutableStateFlow(emptyMap()),
) : ComponentContext by componentContext {
    /** The library route stays in the Decompose back stack while detail covers it. */
    internal val listState = LazyListState()
    val themePreferences = GlobalContext.get().get<com.yfuse.core.data.ThemePreferences>()

    /** Whether this profile may add a server itself; a child profile has to ask for one. */
    val access = GlobalContext.get().get<PersonalLibraryRepository>().policy

    private val playbackSync = runCatching { GlobalContext.get().get<PlaybackSyncManager>() }.getOrNull()

    val store =
        LibraryStoreFactory(
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            cache = GlobalContext.get().get<LibraryCache>(),
            favoriteWriter = GlobalContext.get().get<ServerSyncManager>()::setFavorite,
            forgetHistory =
                if (playbackSync == null) {
                    null
                } else {
                    { serverId, itemId -> playbackSync.forgetResume(serverId, itemId) }
                },
            playedWriter = { server, item, value ->
                // 播放记录 is built from this device's own progress, so the record is written first —
                // the one 首页's 标记已看 and 详情's write — and the server's flag then goes through the
                // sync queue, which keeps it if the server is out of reach.
                playbackSync?.markWatched(
                    mediaKey = item.providerIds.watchKey(item.id),
                    aliases = watchMatchKeys(ownProviderIds = item.providerIds, fallbackId = item.id),
                    watched = value,
                    serverId = server.id,
                    serverItemId = item.id,
                )
                GlobalContext.get().get<ServerSyncManager>().setPlayed(server, item.id, item.title, value)
            },
        ).create()

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
