package com.yfuse.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One reachable address for a saved server.
 *
 * A self-hosted Emby is commonly reachable at several addresses — a LAN address that is fast
 * but only resolves at home, a domain that works anywhere, a CDN or reverse proxy in front of
 * both. They are the same server and the same session: only the host differs, so a route
 * carries no credentials of its own and switching between them never re-authenticates.
 *
 * [id] is assigned once and never derived from [url], because the active selection is stored
 * as an id: deriving it would silently re-point the selection whenever an address is edited.
 */
@Serializable
data class ServerRoute(
    @SerialName("i") val id: String,
    @SerialName("n") val name: String,
    @SerialName("u") val url: String,
) {
    companion object {
        /** The address the server's identity and login were established against. */
        const val PRIMARY_ID: String = "primary"
        const val PRIMARY_NAME: String = "主线路"

        /** Enough for LAN + WAN + a couple of proxies without letting the list grow unbounded. */
        const val MAX_ROUTES: Int = 8
        const val MAX_NAME_CHARS: Int = 20
        const val MAX_URL_CHARS: Int = 2_048

        fun sanitizeName(
            value: String,
            fallback: String = PRIMARY_NAME,
        ): String =
            value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .take(MAX_NAME_CHARS)
                .ifBlank { fallback }

        /** Trailing-slash-free, scheme-checked. Returns null when the address is unusable. */
        fun sanitizeUrl(value: String): String? {
            val normalized = value.trim().trimEnd('/')
            val validScheme =
                normalized.startsWith("https://") || normalized.startsWith("http://")
            return normalized.takeIf {
                validScheme && it.length in 8..MAX_URL_CHARS && it.none(Char::isWhitespace)
            }
        }

        /** Ids are opaque; this only has to avoid colliding inside one server's list. */
        fun nextId(existing: Collection<String>): String {
            if (PRIMARY_ID !in existing) return PRIMARY_ID
            var index = existing.size + 1
            while ("r$index" in existing) index++
            return "r$index"
        }
    }
}

/**
 * Normalizes a route list: primary first, unusable and duplicate addresses dropped, ids made
 * unique, and the whole thing bounded. Returns an empty list when nothing survives, which
 * callers read as "this server has no explicit routes" — see [SavedServer.effectiveRoutes].
 */
fun List<ServerRoute>.normalizedRoutes(): List<ServerRoute> {
    val seenIds = mutableSetOf<String>()
    val seenUrls = mutableSetOf<String>()
    val ordered = sortedBy { if (it.id == ServerRoute.PRIMARY_ID) 0 else 1 }
    return ordered
        .mapNotNull { route ->
            val url = ServerRoute.sanitizeUrl(route.url) ?: return@mapNotNull null
            if (!seenUrls.add(url)) return@mapNotNull null
            val id =
                route.id.takeIf { it.isNotBlank() && seenIds.add(it) }
                    ?: ServerRoute.nextId(seenIds).also { seenIds += it }
            ServerRoute(
                id = id,
                name =
                    ServerRoute.sanitizeName(
                        route.name,
                        fallback =
                            if (id == ServerRoute.PRIMARY_ID) {
                                ServerRoute.PRIMARY_NAME
                            } else {
                                "备用线路"
                            },
                    ),
                url = url,
            )
        }.take(ServerRoute.MAX_ROUTES)
}
