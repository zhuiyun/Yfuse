package com.yfuse.feature.watch

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.sync.WatchInvite

/** An invite resolved against this user's own servers, ready to hand to the player. */
data class ResolvedInvite(
    val server: SavedServer,
    val item: MediaItem,
)

/**
 * Turns an invite's cross-server [WatchInvite.mediaKey] into a concrete item on one of this
 * user's servers.
 *
 * The default server is tried first, then the rest: the common case is that everyone in a
 * room pulls from the same place, and searching that first avoids a round trip per extra
 * server for no reason. A server that errors is skipped rather than aborting the whole
 * lookup — one unreachable server shouldn't stop a title being found on another.
 */
class WatchInviteResolver(
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
) {
    suspend fun resolve(invite: WatchInvite): InviteResolution = resolveInvite(invite, servers(), repo::findByMediaKey)

    /**
     * Same lookup as [resolve], returning the pieces the player needs rather than display
     * copy.
     *
     * Always called with the *room's* key rather than an invite's. An invite is written
     * when the room is created and names the show; the room's timeline names the episode
     * the host is actually on, and landing a guest anywhere else is how two people end up
     * watching different episodes of the same series.
     */
    suspend fun resolveTarget(mediaKey: String): ResolvedInvite? =
        resolveInviteTarget(mediaKey, servers(), repo::findByMediaKey)

    private fun servers(): List<SavedServer> = orderedServers(registry.data.value.servers, registry.defaultServer)
}

/** The default server first, then the rest in their saved order. */
internal fun orderedServers(
    all: List<SavedServer>,
    default: SavedServer?,
): List<SavedServer> = if (default == null) all else listOf(default) + all.filterNot { it.id == default.id }

/** [WatchInviteResolver.resolve] over [servers], already in the order to try, with [lookup] per server. */
internal suspend fun resolveInvite(
    invite: WatchInvite,
    servers: List<SavedServer>,
    lookup: suspend (SavedServer, String) -> Result<MediaItem?>,
): InviteResolution {
    val mediaKey =
        invite.mediaKey
            ?: return InviteResolution.Missing(invite.title)

    if (servers.isEmpty()) {
        AppLog.warning(
            category = "watch_together",
            event = "invite_server_missing",
            message = "Watch-together invite could not resolve without a configured server",
        )
        return InviteResolution.Failed("还没有添加服务器，请先到「服务器」页连接 Emby、Jellyfin 或 Plex 服务器。")
    }

    var sawFailure = false
    for (server in servers) {
        val result = lookup(server, mediaKey)
        val item =
            result.getOrElse {
                sawFailure = true
                AppLog.warning(
                    category = "watch_together",
                    event = "invite_lookup_failed",
                    message = "Watch-together invite lookup failed on a server",
                    throwable = it,
                    attributes = mapOf("serverId" to server.id),
                )
                null
            } ?: continue
        return InviteResolution.Found(
            serverName = server.serverName,
            title = item.title,
            subtitle = item.subtitle ?: item.year?.toString(),
            posterUrl = EmbyImages.poster(server.baseUrl, item, accessToken = server.accessToken),
        )
    }

    return if (sawFailure) {
        AppLog.error(
            category = "watch_together",
            event = "invite_resolution_failed",
            message = "Watch-together invite lookup could not complete",
            attributes = mapOf("serverCount" to servers.size.toString()),
        )
        InviteResolution.Failed("无法确认这部片是否在你的服务器上，稍后重试。")
    } else {
        AppLog.info(
            category = "watch_together",
            event = "invite_media_missing",
            message = "Watch-together invite media was not found on configured servers",
            attributes = mapOf("serverCount" to servers.size.toString()),
        )
        InviteResolution.Missing(invite.title)
    }
}

/** [WatchInviteResolver.resolveTarget] over [servers], already in the order to try. */
internal suspend fun resolveInviteTarget(
    mediaKey: String,
    servers: List<SavedServer>,
    lookup: suspend (SavedServer, String) -> Result<MediaItem?>,
): ResolvedInvite? {
    var failures = 0
    for (server in servers) {
        val result = lookup(server, mediaKey)
        if (result.isFailure) failures++
        val item = result.getOrNull() ?: continue
        return ResolvedInvite(server, item)
    }
    if (failures > 0) {
        AppLog.warning(
            category = "watch_together",
            event = "invite_target_unresolved",
            message = "Watch-together playback target could not be resolved",
            attributes = mapOf("failedServerCount" to failures.toString()),
        )
    }
    return null
}
