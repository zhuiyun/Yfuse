package com.yfuse.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class MediaServerKind {
    Emby,
    Jellyfin,
    /** Reserved for the provider adapter; Emby-compatible endpoints never masquerade as Plex. */
    Plex,
}

/** A server the user has logged into, with its saved session and user-visible name. */
@Serializable
data class SavedServer(
    val id: String,
    /**
     * The address currently in use. Every network call reads this, so switching routes is a
     * write here rather than a change of [id]: the identity, its caches, and its downloads
     * must survive a failover to a backup address.
     */
    val baseUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val kind: MediaServerKind = MediaServerKind.Emby,
    /** Previous connection-derived ids retained when this saved server is edited. */
    val previousIds: Set<String> = emptySet(),
    /** Empty for a server saved before multi-route; see [effectiveRoutes]. */
    val routes: List<ServerRoute> = emptyList(),
    /** Which of [effectiveRoutes] [baseUrl] currently points at. */
    val activeRouteId: String? = null,
    /** A single emoji shown instead of the name's first letter. */
    val iconEmoji: String? = null,
    /** ARGB tint for the icon and the card wash; null derives one from [serverName]. */
    val iconTint: Long? = null,
    /**
     * Device-local acknowledgement for sending credentials over a trusted LAN HTTP route.
     *
     * This must never travel in [ServersData]: a different device has a different network
     * boundary and must make its own decision. [ServerRegistry] persists it separately in the
     * local, token-free metadata document.
     */
    @Transient
    val localCleartextConfirmed: Boolean = false,
) {
    /**
     * The routes as the user sees them. A server saved before multi-route existed has one
     * implicit 主线路 at its saved address, so the rest of the app never branches on whether
     * the stored list happens to be empty.
     */
    val effectiveRoutes: List<ServerRoute>
        get() =
            routes.ifEmpty {
                listOf(ServerRoute(ServerRoute.PRIMARY_ID, ServerRoute.PRIMARY_NAME, baseUrl))
            }

    /**
     * The address the session was established against, and the one [id] derives from. Editing
     * the connection edits this; a failover to a backup route must not.
     */
    val primaryUrl: String
        get() = effectiveRoutes.first().url

    val activeRoute: ServerRoute
        get() =
            effectiveRoutes.let { list ->
                list.firstOrNull { it.id == activeRouteId }
                    ?: list.firstOrNull { it.url == baseUrl }
                    ?: list.first()
            }

    val hasBackupRoutes: Boolean get() = effectiveRoutes.size > 1

    /** Repoints [baseUrl] at [route] without disturbing the server's identity. */
    fun activating(route: ServerRoute): SavedServer = copy(baseUrl = route.url, activeRouteId = route.id)

    /**
     * Re-establishes the invariant that [baseUrl] is the active route's address. Servers with
     * no explicit routes are left alone so they keep persisting as a bare address.
     */
    fun withNormalizedRoutes(): SavedServer {
        val normalized = routes.normalizedRoutes()
        if (normalized.isEmpty()) {
            return if (routes.isEmpty() && activeRouteId == null) {
                this
            } else {
                copy(routes = emptyList(), activeRouteId = null)
            }
        }
        val active =
            normalized.firstOrNull { it.id == activeRouteId }
                ?: normalized.firstOrNull { it.url == baseUrl }
                ?: normalized.first()
        return copy(routes = normalized, activeRouteId = active.id, baseUrl = active.url)
    }

    companion object {
        /** Stable id so re-logging into the same server+user updates one entry. */
        fun idOf(
            baseUrl: String,
            userId: String,
        ): String = "$baseUrl#$userId"
    }
}

/** The full set of saved servers plus which one is currently the default. */
@Serializable
data class ServersData(
    val servers: List<SavedServer> = emptyList(),
    val defaultServerId: String? = null,
) {
    val defaultServer: SavedServer?
        get() = servers.firstOrNull { it.id == defaultServerId } ?: servers.firstOrNull()
}
