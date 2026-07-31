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
    suspend fun resolve(invite: WatchInvite): InviteResolution {
        val mediaKey = invite.mediaKey
            ?: return InviteResolution.Missing(invite.title)

        val servers = orderedServers()
        if (servers.isEmpty()) {
            AppLog.warning(
                category = "watch_together",
                event = "invite_server_missing",
                message = "Watch-together invite could not resolve without a configured server",
            )
            return InviteResolution.Failed("还没有添加服务器，请先到「我的」登录一台 Emby 服务器。")
        }

        var sawFailure = false
        for (server in servers) {
            val result = repo.findByMediaKey(server, mediaKey)
            val item = result.getOrElse {
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

    /** Same lookup, but returning the pieces the player needs rather than display copy. */
    suspend fun resolveTarget(invite: WatchInvite): ResolvedInvite? {
        val mediaKey = invite.mediaKey ?: return null
        return resolveTarget(mediaKey)
    }

    /**
     * [resolveTarget] for a room joined without an invite — by typing its code — where the
     * only thing naming the media is the room's own timeline.
     */
    suspend fun resolveTarget(mediaKey: String): ResolvedInvite? {
        var failures = 0
        for (server in orderedServers()) {
            val result = repo.findByMediaKey(server, mediaKey)
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

    private fun orderedServers(): List<SavedServer> {
        val all = registry.data.value.servers
        val default = registry.defaultServer
        return if (default == null) all else listOf(default) + all.filterNot { it.id == default.id }
    }
}
