package com.yfuse.feature.library

import com.yfuse.core.data.CrossServerMediaGroup
import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.data.aggregateCrossServerMedia
import com.yfuse.core.model.LibraryPage
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class UnifiedLibraryType(
    val label: String,
    val itemType: String?,
) {
    All("全部", null),
    Movies("电影", "Movie"),
    Series("剧集", "Series"),
}

data class UnifiedLibraryQuery(
    val type: UnifiedLibraryType = UnifiedLibraryType.All,
    val unplayedOnly: Boolean = false,
)

data class UnifiedLibraryState(
    val loading: Boolean = false,
    val groups: List<CrossServerMediaGroup> = emptyList(),
    val failures: Map<String, String> = emptyMap(),
    val libraryCount: Int = 0,
    val completedLibraries: Int = 0,
    val hasMore: Boolean = false,
)

private data class LibraryCursor(
    val server: SavedServer,
    val library: MediaLibrary,
    val offset: Int = 0,
    val complete: Boolean = false,
    val failed: Boolean = false,
) {
    val key: String get() = "${server.id}/${library.id}"
    val label: String get() = "${server.serverName} · ${library.name}"
}

/** Pages a bounded number of sources per gesture; it never scans an entire server eagerly. */
class UnifiedLibraryPager(
    private val libraries: suspend (SavedServer) -> Result<List<MediaLibrary>>,
    private val page: suspend (SavedServer, String, Int, Int, Boolean) -> Result<LibraryPage>,
) {
    private val mutableState = MutableStateFlow(UnifiedLibraryState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0
    private var query = UnifiedLibraryQuery()
    private var cursors = emptyList<LibraryCursor>()
    private var hits = linkedMapOf<Pair<String, String>, CrossServerMediaHit>()
    private var nextCursor = 0

    suspend fun reset(
        servers: List<SavedServer>,
        newQuery: UnifiedLibraryQuery,
    ) {
        val request = ++generation
        query = newQuery
        cursors = emptyList()
        hits = linkedMapOf()
        nextCursor = 0
        mutableState.value = UnifiedLibraryState(loading = true)
        try {
            val results =
                coroutineScope {
                    val permits = Semaphore(4)
                    servers
                        .map { server ->
                            async { permits.withPermit { server to libraries(server) } }
                        }.awaitAll()
                }
            if (request != generation) return
            val failures = linkedMapOf<String, String>()
            cursors =
                results.flatMap { (server, result) ->
                    result.fold(
                        onSuccess = { values ->
                            values
                                .distinctBy { it.id }
                                .filter { library ->
                                    when (newQuery.type) {
                                        UnifiedLibraryType.All -> true
                                        UnifiedLibraryType.Movies -> library.collectionType !in setOf("tvshows", "show")
                                        UnifiedLibraryType.Series -> library.collectionType !in setOf("movies", "movie")
                                    }
                                }.map { LibraryCursor(server, it) }
                        },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            failures[server.serverName] = error.toUserMessage("无法连接服务器，请重试")
                            emptyList()
                        },
                    )
                }
            mutableState.value =
                UnifiedLibraryState(
                    failures = failures,
                    libraryCount = cursors.size,
                    hasMore = cursors.isNotEmpty(),
                )
        } finally {
            if (request == generation) mutableState.value = mutableState.value.copy(loading = false)
        }
    }

    suspend fun loadMore(retryFailures: Boolean = false) =
        mutex.withLock {
            if (mutableState.value.loading || cursors.isEmpty()) return@withLock
            val request = generation
            if (retryFailures) cursors = cursors.map { it.copy(failed = false) }
            val ordered = (cursors.indices).map { (it + nextCursor) % cursors.size }
            val selected = ordered.filter { !cursors[it].complete && !cursors[it].failed }.take(4)
            if (selected.isEmpty()) return@withLock
            val requested = selected.map { cursors[it] }
            val requestedQuery = query
            mutableState.value = mutableState.value.copy(loading = true)
            try {
                val results =
                    coroutineScope {
                        requested
                            .map { cursor ->
                                async {
                                    cursor to
                                        page(
                                            cursor.server,
                                            cursor.library.id,
                                            cursor.offset,
                                            40,
                                            requestedQuery.unplayedOnly,
                                        )
                                }
                            }.awaitAll()
                    }
                if (generation != request) return@withLock
                val failures = mutableState.value.failures.toMutableMap()
                val updated = cursors.toMutableList()
                results.forEachIndexed { index, (cursor, result) ->
                    result.fold(
                        onSuccess = { response ->
                            // An invalid offset must not quietly duplicate the first page forever.
                            if (response.startIndex != cursor.offset) {
                                failures[cursor.label] = "服务器返回了重复分页，请刷新后重试"
                                updated[selected[index]] = cursor.copy(failed = true)
                            } else {
                                failures.remove(cursor.label)
                                response.items
                                    .filter { item ->
                                        item.type in setOf("Movie", "Series") &&
                                            (
                                                requestedQuery.type.itemType == null ||
                                                    item.type == requestedQuery.type.itemType
                                            ) &&
                                            (!requestedQuery.unplayedOnly || !item.played)
                                    }.forEach { item ->
                                        hits[cursor.server.id to item.id] =
                                            CrossServerMediaHit(
                                                cursor.server.id,
                                                cursor.server.serverName,
                                                item.copy(overview = null),
                                            )
                                    }
                                val next = cursor.offset + response.items.size
                                updated[selected[index]] =
                                    cursor.copy(
                                        offset = next,
                                        complete = response.items.isEmpty() || next >= response.totalCount,
                                    )
                            }
                        },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            failures[cursor.label] = error.toUserMessage("无法连接服务器，请重试")
                            updated[selected[index]] = cursor.copy(failed = true)
                        },
                    )
                }
                cursors = updated
                nextCursor = (selected.last() + 1) % cursors.size
                mutableState.value =
                    UnifiedLibraryState(
                        groups =
                            aggregateCrossServerMedia(
                                hits.values.toList(),
                            ).sortedBy {
                                it.recommended.item.title
                                    .lowercase()
                            },
                        failures = failures,
                        libraryCount = cursors.size,
                        completedLibraries = cursors.count { it.complete },
                        hasMore = cursors.any { !it.complete && !it.failed },
                    )
            } finally {
                if (generation == request) mutableState.value = mutableState.value.copy(loading = false)
            }
        }
}
