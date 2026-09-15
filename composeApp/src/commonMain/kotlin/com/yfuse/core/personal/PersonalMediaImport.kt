package com.yfuse.core.personal

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.sync.watchKey
import kotlinx.coroutines.CancellationException

fun MediaItem.toPersonalMediaRef(serverId: String): PersonalMediaRef =
    PersonalMediaRef(
        mediaKey = providerIds.watchKey(id),
        title = title,
        mediaType = type,
        tmdbId =
            providerIds.entries
                .firstOrNull { it.key.equals("tmdb", true) }
                ?.value
                ?.toIntOrNull(),
        year = year,
        serverId = serverId,
        serverItemId = id,
    )

fun TmdbItem.toPersonalMediaRef(): PersonalMediaRef =
    PersonalMediaRef(
        mediaKey = "tmdb:$id",
        title = title,
        mediaType = if (mediaType == "tv") "Series" else "Movie",
        tmdbId = id,
        year = year?.toIntOrNull(),
        posterPath = posterPath,
    )

/** Imports server-owned lists without replacing any personal choice or deletion. */
suspend fun PersonalLibraryRepository.importServerCollections(
    repo: EmbyRepository,
    servers: List<SavedServer>,
): Result<Int> =
    runCatching {
        val token = scopeToken
        var imported = 0
        servers.filter { canAccessServer(it.id) && it.kind != MediaServerKind.Plex }.forEach { server ->
            listOf(
                "__yfuse_favorites__" to PersonalCollection.Favorite,
                "__yfuse_watch_later__" to PersonalCollection.WatchLater,
            ).forEach { (id, kind) ->
                var offset = 0
                repeat(10) {
                    if (offset < 0) return@repeat
                    val page = repo.libraryItems(server, id, startIndex = offset, limit = 50).getOrThrow()
                    check(token == scopeToken) { "资料已切换，已停止导入" }
                    page.items.forEach { item ->
                        if (kind == PersonalCollection.WatchLater) {
                            if (importWatchLater(item.toPersonalMediaRef(server.id))) imported++
                        } else if (importFavorite(item.toPersonalMediaRef(server.id))) {
                            imported++
                        }
                    }
                    offset = if (page.items.size < 50) -1 else offset + page.items.size
                }
                check(offset < 0) { "本次已处理 500 条；清单较大，请分批整理后导入，已导入的条目会保留" }
            }
        }
        imported
    }.onFailure { if (it is CancellationException) throw it }
