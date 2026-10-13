package com.yfuse.feature.history

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.personal.LocalViewingStore
import com.yfuse.core.personal.PersonalLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ComponentContext by componentContext {
    // Detail covers this route without destroying its scroll position.
    internal val listState = LazyListState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val metadataSlots = Semaphore(3)
    private val requests = mutableMapOf<PlaybackHistoryMetadataKey, Deferred<PlaybackHistoryMetadata?>>()
    private val origins = mutableMapOf<PlaybackHistoryMetadataKey, SavedServer>()
    private val failedAt = mutableMapOf<PlaybackHistoryMetadataKey, Long>()
    private val detailRequests = mutableMapOf<PlaybackHistoryMetadataKey, DetailRequest>()
    private var metadataScope = personal.scopeToken
    private val posterStates = mutableMapOf<String, PlaybackHistoryPosterState>()
    private val loadedMetadata = MutableStateFlow<Map<PlaybackHistoryMetadataKey, PlaybackHistoryMetadata?>>(emptyMap())
    internal val metadata = loadedMetadata.asStateFlow()

    init {
        lifecycle.doOnDestroy(scope::cancel)
    }

    internal fun metadataKey(row: PlaybackHistoryRow): PlaybackHistoryMetadataKey? {
        val server = playbackHistoryServer(row.entry.media, registry.data.value.servers) ?: return null
        val itemId =
            row.entry.media.serverItemId
                ?.takeIf(String::isNotBlank) ?: return null
        if (row.entry.profileId != personal.activeProfileId || !personal.canAccessServer(server.id)) return null
        return PlaybackHistoryMetadataKey(personal.scopeToken, server.id, itemId)
    }

    /** Keep only the viewport and its small prefetch margin in the request queue. */
    internal fun updateVisibleRows(rows: List<PlaybackHistoryRow>) {
        if (metadataScope != personal.scopeToken) {
            metadataScope = personal.scopeToken
            loadedMetadata.value = emptyMap()
            failedAt.clear()
            posterStates.clear()
        }
        val wanted = rows.mapNotNull(::metadataKey).toSet()
        requests.toMap().forEach { (key, request) ->
            if (key !in wanted || origins[key] != registry.serverById(key.serverId)) {
                requests.remove(key)
                request.cancel()
            }
        }
        rows.forEach { metadataRequest(it) }
    }

    internal fun posterState(row: PlaybackHistoryRow): PlaybackHistoryPosterState =
        posterStates.getOrPut("${personal.scopeToken}:${row.entry.identity}") { PlaybackHistoryPosterState() }

    internal fun retryMetadata(row: PlaybackHistoryRow) {
        posterState(row).retry()
        metadataRequest(row, force = true)
    }

    /** Successes survive detail return; a failed request can be retried after its cooldown. */
    internal suspend fun loadMetadata(row: PlaybackHistoryRow) {
        metadataRequest(row)?.await()
    }

    private fun metadataRequest(
        row: PlaybackHistoryRow,
        force: Boolean = false,
    ): Deferred<PlaybackHistoryMetadata?>? {
        val key = metadataKey(row) ?: return null
        val server = registry.serverById(key.serverId) ?: return null
        if (origins[key] != server) {
            requests.remove(key)?.cancel()
            loadedMetadata.value = loadedMetadata.value - key
            failedAt.remove(key)
        }
        requests[key]?.let { return it }
        if (!force && loadedMetadata.value[key]?.complete == true) return null
        if (!force && failedAt[key]?.let { nowMs() - it < 30_000L } == true) return null
        if (loadedMetadata.value.containsKey(key) && loadedMetadata.value[key] == null) {
            loadedMetadata.value = loadedMetadata.value - key
        }
        origins[key] = server
        val request =
            scope.async(start = CoroutineStart.LAZY) {
                metadataSlots.withPermit {
                    if (key.scopeToken != personal.scopeToken ||
                        !personal.canAccessServer(server.id)
                    ) {
                        return@withPermit null
                    }
                    val item = loadDetail(server, key.itemId, key.scopeToken)
                    val seriesId = item?.seriesId?.takeIf { it.isNotBlank() && it != item.id }
                    val series = seriesId?.let { loadDetail(server, it, key.scopeToken) }
                    currentCoroutineContext().ensureActive()
                    val result =
                        item?.let {
                            PlaybackHistoryMetadata(
                                it,
                                series,
                                complete =
                                    seriesId == null || series != null,
                            )
                        }
                    if (key.scopeToken == personal.scopeToken &&
                        personal.canAccessServer(server.id) &&
                        registry.serverById(server.id) == server
                    ) {
                        if (result?.complete == true) failedAt.remove(key) else failedAt[key] = nowMs()
                        loadedMetadata.value = loadedMetadata.value + (key to result)
                    }
                    result
                }
            }
        requests[key] = request
        request.invokeOnCompletion { if (requests[key] === request) requests.remove(key) }
        request.start()
        return request
    }

    private class DetailRequest(
        val result: Deferred<MediaDetail?>,
        val server: SavedServer,
        var readers: Int = 0,
    )

    /** Adjacent episodes share their parent-show request, without keeping orphaned work alive. */
    private suspend fun loadDetail(
        server: SavedServer,
        itemId: String,
        scopeToken: String,
    ): MediaDetail? {
        repo.cachedItemDetail(server, itemId)?.let { return it }
        val key = PlaybackHistoryMetadataKey(scopeToken, server.id, itemId)
        detailRequests[key]?.takeIf { it.server != server || it.result.isCancelled }?.let {
            detailRequests.remove(key)
        }
        val request =
            detailRequests.getOrPut(key) {
                DetailRequest(
                    scope.async(start = CoroutineStart.LAZY) {
                        repo
                            .itemDetail(
                                server,
                                itemId,
                                includeInheritedPeople = false,
                                includePlaybackFields = false,
                            ).getOrNull()
                    },
                    server,
                )
            }
        request.readers++
        request.result.start()
        try {
            return request.result.await()
        } finally {
            request.readers--
            if (request.readers == 0) {
                if (!request.result.isCompleted) request.result.cancel()
                if (detailRequests[key] === request) detailRequests.remove(key)
            }
        }
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
