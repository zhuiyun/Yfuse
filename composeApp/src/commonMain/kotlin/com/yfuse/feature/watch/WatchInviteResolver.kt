package com.yfuse.feature.watch

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
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
    suspend fun resolve(invite: WatchInvite): InviteResolution {
        val mediaKey = invite.mediaKey
            ?: return InviteResolution.Missing(invite.title)

        val servers = orderedServers()
        if (servers.isEmpty()) {
            return InviteResolution.Failed("还没有添加服务器，请先到「我的」登录一台 Emby 服务器。")
        }

        var sawFailure = false
        for (server in servers) {
            val result = repo.findByMediaKey(server, mediaKey)
            val item = result.getOrElse {
                sawFailure = true
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
            InviteResolution.Failed("无法确认这部片是否在你的服务器上，稍后重试。")
        } else {
            InviteResolution.Missing(invite.title)
        }
    }

    /** Same lookup, but returning the pieces the player needs rather than display copy. */
    suspend fun resolveTarget(invite: WatchInvite): ResolvedInvite? {
        val mediaKey = invite.mediaKey ?: return null
        for (server in orderedServers()) {
            val item = repo.findByMediaKey(server, mediaKey).getOrNull() ?: continue
            return ResolvedInvite(server, item)
        }
        return null
    }

    private fun orderedServers(): List<SavedServer> {
        val all = registry.data.value.servers
        val default = registry.defaultServer
        return if (default == null) all else listOf(default) + all.filterNot { it.id == default.id }
    }
}
