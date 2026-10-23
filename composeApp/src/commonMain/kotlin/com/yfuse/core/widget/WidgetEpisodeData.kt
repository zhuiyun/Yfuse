package com.yfuse.core.widget

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.sync.playback.PlaybackSyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

internal data class WidgetEpisodeProgress(
    val played: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

internal data class WidgetEpisodeDirectory(
    val series: WidgetMedia,
    val seasons: List<WidgetMedia>,
    val episodes: List<WidgetMedia>,
)

/** The host owns library access; neither credentials nor this interface cross the JS bridge. */
internal interface WidgetEpisodeLibrary {
    val revision: StateFlow<Long>

    fun scopeKey(): String

    suspend fun directory(item: WidgetMedia): WidgetEpisodeDirectory?

    suspend fun nextUp(serverId: String): Set<String>

    fun progress(target: WidgetLibraryTarget): WidgetEpisodeProgress?
}

internal data class WidgetEpisodeResult(
    val item: WidgetMedia,
    val message: String? = null,
    val retryable: Boolean = false,
)

/** Bounded, shared directory cache. Only a visible episode layout asks for a title. */
internal class WidgetEpisodeData(
    private val library: WidgetEpisodeLibrary,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val revision: StateFlow<Long> get() = library.revision
    val partitionKey: String get() = library.scopeKey()
    private val mutex = Mutex()
    private val permits = Semaphore(2)

    private data class Cached(
        val created: Long,
        val value: Deferred<Result<WidgetEpisodeDirectory?>>,
    )

    private val directories = linkedMapOf<String, Cached>()

    private data class NextCached(
        val created: Long,
        val value: Deferred<Result<Set<String>>>,
    )

    private val nextItems = linkedMapOf<String, NextCached>()

    suspend fun load(
        item: WidgetMedia,
        includeNext: Boolean,
        refresh: Boolean = false,
    ): WidgetEpisodeResult {
        // A script's own seasonal tree remains the source of truth for that source.
        if (item.children.isNotEmpty() && item.widgetEpisodeItems().isNotEmpty()) return WidgetEpisodeResult(item)
        val identity =
            item.tmdbDetailItemOrNull()?.takeIf { it.mediaType == "tv" }
                ?: return WidgetEpisodeResult(item, "此来源尚未提供分集资料")
        val partition = library.scopeKey()
        val key = "$partition:tv:${identity.id}"
        return try {
            val pending =
                mutex.withLock {
                    val old = directories[key]
                    if (!refresh && old != null && now() - old.created < 300_000L) {
                        old.value
                    } else {
                        scope
                            .async {
                                runCatching { permits.withPermit { withTimeout(25_000L) { library.directory(item) } } }
                            }.also {
                                directories[key] = Cached(now(), it)
                                while (directories.size > 48) directories.remove(directories.keys.first())
                            }
                    }
                }
            val directory = pending.await().getOrThrow()
            if (partition != library.scopeKey()) return WidgetEpisodeResult(item)
            if (directory == null) {
                WidgetEpisodeResult(item, "媒体库中暂无匹配的剧集", retryable = true)
            } else {
                val nativeTarget = directory.series.libraryTarget
                var nextFailed = false
                val next =
                    if (includeNext && nativeTarget != null) {
                        try {
                            val nextKey = "$partition:${nativeTarget.serverId}"
                            val request =
                                mutex.withLock {
                                    if (refresh) nextItems.remove(nextKey)
                                    val cached = nextItems[nextKey]
                                    if (cached != null && now() - cached.created < 60_000L) {
                                        cached.value
                                    } else {
                                        scope
                                            .async {
                                                runCatching {
                                                    withTimeout(
                                                        20_000L,
                                                    ) { library.nextUp(nativeTarget.serverId) }
                                                }
                                            }.also {
                                                nextItems[nextKey] = NextCached(now(), it)
                                                while (nextItems.size > 8) nextItems.remove(nextItems.keys.first())
                                            }
                                    }
                                }
                            request.await().getOrThrow()
                        } catch (failure: CancellationException) {
                            if (failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                            nextFailed = true
                            emptySet()
                        } catch (_: Exception) {
                            nextFailed = true
                            emptySet()
                        }
                    } else {
                        emptySet()
                    }
                if (partition != library.scopeKey()) return WidgetEpisodeResult(item)
                val episodes =
                    directory.episodes.map { episode ->
                        projectProgress(
                            episode.copy(
                                poster = episode.poster.ifBlank { item.poster },
                                backdrop = episode.backdrop.ifBlank { item.backdrop },
                            ),
                        )
                    }
                val confirmedNext =
                    episodes.firstOrNull {
                        it.available != false && it.played != true && it.libraryTarget?.itemId in next
                    }
                val groups = episodes.groupBy { it.season }
                val seasons =
                    groups.map { (number, entries) ->
                        val supplied = directory.seasons.firstOrNull { it.season == number }
                        (
                            supplied ?: directory.series.copy(
                                id = "${directory.series.id}:season:${number ?: "unknown"}",
                                title = number?.let { "第 $it 季" } ?: "剧集",
                                season = number,
                            )
                        ).copy(children = entries, poster = supplied?.poster?.ifBlank { item.poster } ?: item.poster)
                    }
                WidgetEpisodeResult(
                    item.copy(
                        libraryTarget = directory.series.libraryTarget,
                        children = seasons,
                        nextEpisodeId = confirmedNext?.id,
                    ),
                    message = if (nextFailed) "下一集暂未确认，可重试" else null,
                    retryable = nextFailed,
                )
            }
        } catch (failure: CancellationException) {
            // A timeout must remain retryable, while caller cancellation still propagates.
            if (failure is kotlinx.coroutines.TimeoutCancellationException) {
                mutex.withLock { directories.remove(key) }
                WidgetEpisodeResult(item, "分集资料加载超时", retryable = true)
            } else {
                throw failure
            }
        } catch (_: Exception) {
            mutex.withLock { directories.remove(key) }
            WidgetEpisodeResult(item, "分集资料暂未加载，可重试", retryable = true)
        }
    }

    private fun projectProgress(item: WidgetMedia): WidgetMedia {
        val state = item.libraryTarget?.let(library::progress)
        val progress =
            when {
                state == null -> null
                state.played -> 1f
                state.positionMs > 0L && (state.durationMs > 0L || (item.durationMinutes ?: 0) > 0) ->
                    (
                        state.positionMs.toDouble() /
                            (state.durationMs.takeIf { it > 0L } ?: (item.durationMinutes!!.toLong() * 60_000L))
                    ).toFloat().coerceIn(0f, 1f)
                else -> null
            }
        return item.copy(played = state?.played, progress = progress)
    }
}

/** Only explicit episode coordinates count; arbitrary children can be collections or streams. */
internal fun WidgetMedia.widgetEpisodeItems(): List<WidgetMedia> {
    val result = mutableListOf<WidgetMedia>()

    fun visit(
        item: WidgetMedia,
        parent: WidgetMedia,
        depth: Int,
    ) {
        if (depth > 4 || result.size >= 500) return
        val inherited =
            item.copy(
                season = item.season ?: parent.season,
                poster = item.poster.ifBlank { parent.poster },
                backdrop = item.backdrop.ifBlank { parent.backdrop },
                tmdbId = item.tmdbId.ifBlank { parent.tmdbId },
                mediaType = if (parent.mediaType in setOf("tv", "series")) "tv" else item.mediaType,
                headers = parent.headers + item.headers,
            )
        if (inherited.episode != null) result += inherited
        item.children.forEach { visit(it, inherited, depth + 1) }
    }
    children.forEach { visit(it, this, 0) }
    if (episode != null && result.isEmpty()) result += this
    return result.distinctBy { it.id }.sortedWith(
        compareBy({ it.season ?: Int.MAX_VALUE }, {
            it.episode
                ?: Int.MAX_VALUE
        }),
    )
}

internal fun WidgetMedia.confirmedNextWidgetEpisode(): WidgetMedia? =
    widgetEpisodeItems().firstOrNull {
        it.id == nextEpisodeId && it.available != false && it.played != true
    } ?: widgetEpisodeItems().singleOrNull {
        it.played == false && it.progress != null && it.progress > 0f && it.progress < 1f && it.available != false
    }

internal expect fun widgetEpisodeData(): WidgetEpisodeData

/** Library metadata and progress stay in the trusted host, outside every QuickJS invocation. */
internal class NativeWidgetEpisodeLibrary(
    private val repository: EmbyRepository,
    private val registry: ServerRegistry,
    private val playback: PlaybackSyncStore,
    scope: CoroutineScope,
) : WidgetEpisodeLibrary {
    private var version = 0L
    override val revision: StateFlow<Long> =
        combine(registry.data, playback.changes) { _, _ -> ++version }
            .stateIn(scope, SharingStarted.Eagerly, 0L)

    override fun scopeKey(): String = "${playback.scopeToken}:${registry.data.value.hashCode()}"

    override suspend fun directory(item: WidgetMedia): WidgetEpisodeDirectory? {
        val identity = item.tmdbDetailItemOrNull()?.takeIf { it.mediaType == "tv" } ?: return null
        val snapshot = registry.data.value
        val servers = snapshot.servers.sortedBy { it.id != snapshot.defaultServer?.id }
        var failed = false
        for (server in servers) {
            val match = repository.findByTmdbId(server, identity.id, "tv")
            val found =
                match.getOrElse {
                    if (it is CancellationException) throw it
                    failed = true
                    null
                } ?: continue
            if (!found.type.equals("Series", ignoreCase = true)) continue
            val episodes = repository.episodes(server, found.id, seasonId = null).getOrThrow()
            val series =
                WidgetMedia(
                    id = "native:${server.id}:${found.id}",
                    title = found.title,
                    mediaType = "tv",
                    type = "tmdb",
                    tmdbId = identity.id.toString(),
                    poster = EmbyImages.poster(server.baseUrl, found, accessToken = server.accessToken).orEmpty(),
                    backdrop = EmbyImages.backdrop(server.baseUrl, found, accessToken = server.accessToken).orEmpty(),
                    libraryTarget = WidgetLibraryTarget(server.id, found.id),
                )
            val seasons =
                repository.seasons(server, found.id).getOrElse {
                    if (it is CancellationException) throw it
                    emptyList()
                }
            return WidgetEpisodeDirectory(
                series,
                seasons.map { season ->
                    series.copy(
                        id = "native:${server.id}:${found.id}:season:${season.id}",
                        title = season.name,
                        season = season.indexNumber,
                        poster =
                            season.posterTag?.let {
                                EmbyImages.primary(server.baseUrl, season.id, it, accessToken = server.accessToken)
                            } ?: series.poster,
                    )
                },
                episodes.map { episode ->
                    WidgetMedia(
                        id = "native:${server.id}:${episode.id}",
                        title = episode.name,
                        poster = series.poster,
                        backdrop =
                            episode.primaryTag?.let {
                                EmbyImages.primary(server.baseUrl, episode.id, it, accessToken = server.accessToken)
                            } ?: series.backdrop,
                        description = episode.overview.orEmpty(),
                        year = found.year?.toString().orEmpty(),
                        mediaType = "tv",
                        type = "tmdb",
                        tmdbId = identity.id.toString(),
                        date = episode.premiereDate.orEmpty(),
                        group = found.title,
                        season = episode.seasonNumber,
                        episode = episode.indexNumber,
                        available = !episode.missing,
                        durationMinutes = episode.runtimeMinutes?.takeIf { it > 0 },
                        libraryTarget =
                            if (episode.missing) null else WidgetLibraryTarget(server.id, episode.id),
                    )
                },
            )
        }
        check(!failed) { "Library lookup is temporarily unavailable" }
        return null
    }

    override suspend fun nextUp(serverId: String): Set<String> {
        val server =
            registry.data.value.servers
                .firstOrNull { it.id == serverId } ?: return emptySet()
        return repository
            .nextUpEpisodes(server, limit = 100)
            .getOrThrow()
            .map { it.id }
            .toSet()
    }

    override fun progress(target: WidgetLibraryTarget): WidgetEpisodeProgress? =
        playback.stateForServerItem(target.serverId, target.itemId)?.let {
            WidgetEpisodeProgress(it.played, it.positionMs, it.durationMs)
        }
}
