package com.yfuse.feature.history

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.personal.LocalViewingStore
import com.yfuse.core.personal.PersonalLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.context.GlobalContext

class PlaybackHistoryComponent(
    componentContext: ComponentContext,
    val registry: ServerRegistry,
    private val repo: EmbyRepository,
    val onBack: () -> Unit,
    private val onOpenItem: (serverId: String, itemId: String) -> Unit,
    private val onOpenMetadata: (TmdbItem) -> Unit,
    val personal: PersonalLibraryRepository = GlobalContext.get().get(),
    val viewing: LocalViewingStore = GlobalContext.get().get(),
) : ComponentContext by componentContext {
    // Detail covers this route without destroying its scroll position.
    internal val listState = LazyListState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val metadataSlots = Semaphore(3)
    private val requests = mutableMapOf<PlaybackHistoryMetadataKey, Deferred<PlaybackHistoryMetadata?>>()
    private val loadedMetadata = MutableStateFlow<Map<PlaybackHistoryMetadataKey, PlaybackHistoryMetadata?>>(emptyMap())
    internal val metadata = loadedMetadata.asStateFlow()

    init {
        lifecycle.doOnDestroy(scope::cancel)
    }

    /** Lazy cards request only their own synopsis; completed requests survive a trip to detail. */
    internal suspend fun loadMetadata(row: PlaybackHistoryRow) {
        val server = playbackHistoryServer(row.entry.media, registry.data.value.servers) ?: return
        val itemId =
            row.entry.media.serverItemId
                ?.takeIf(String::isNotBlank) ?: return
        if (row.entry.profileId != personal.activeProfileId || !personal.canAccessServer(server.id)) return
        val key = PlaybackHistoryMetadataKey(personal.scopeToken, server.id, itemId)
        requests
            .getOrPut(key) {
                scope.async {
                    metadataSlots.withPermit {
                        if (key.scopeToken != personal.scopeToken ||
                            !personal.canAccessServer(server.id)
                        ) {
                            return@withPermit null
                        }
                        val item =
                            repo.cachedItemDetail(server, itemId)
                                ?: repo
                                    .itemDetail(
                                        server,
                                        itemId,
                                        includeInheritedPeople = false,
                                        includePlaybackFields = false,
                                    ).getOrNull()
                        val series =
                            item?.seriesId?.takeIf { it.isNotBlank() && it != item.id }?.let {
                                repo.cachedItemDetail(server, it)
                                    ?: repo
                                        .itemDetail(
                                            server,
                                            it,
                                            includeInheritedPeople = false,
                                            includePlaybackFields = false,
                                        ).getOrNull()
                            }
                        currentCoroutineContext().ensureActive()
                        val result = item?.let { PlaybackHistoryMetadata(it, series) }
                        if (key.scopeToken == personal.scopeToken && personal.canAccessServer(server.id)) {
                            loadedMetadata.value = loadedMetadata.value + (key to result)
                        }
                        result
                    }
                }
            }.await()
    }

    /** Re-resolve access at the tap, since the server or active profile may have changed. */
    internal fun open(row: PlaybackHistoryRow): Boolean {
        if (row.entry.profileId != personal.activeProfileId) return false
        val media = row.entry.media
        if (media.serverId?.let(personal::canAccessServer) == false) return false
        val server = playbackHistoryServer(media, registry.data.value.servers)
        val itemId = media.serverItemId?.takeIf(String::isNotBlank)
        if (server != null && itemId != null) {
            onOpenItem(server.id, itemId)
            return true
        }
        val tmdbId = media.tmdbId?.takeIf { it > 0 } ?: return false
        val type =
            when (media.mediaType.lowercase()) {
                "movie" -> "movie"
                "series", "tv" -> "tv"
                else -> return false
            }
        onOpenMetadata(
            TmdbItem(
                id = tmdbId,
                title = media.title,
                overview = null,
                posterPath = media.posterPath,
                backdropPath = null,
                year = media.year?.toString(),
                mediaType = type,
                rating = null,
            ),
        )
        return true
    }
}
