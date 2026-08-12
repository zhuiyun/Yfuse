package com.yfuse.feature.library

import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val CONTAINER_ROUTE_PREFIX = "__yfuse_container_v1__:"
private const val CONTAINER_DIRECTORY_ROUTE_PREFIX = "__yfuse_container_directory_v1__:"

/**
 * An opaque value carried by the existing `Grid(libraryId, title)` route.
 *
 * Keeping the server id in the serialized value prevents a late tap or response from being
 * redirected to whichever server became default after the home content was rendered.
 */
@Serializable
internal data class LibraryContainerRoute(
    val serverId: String,
    val containerId: String,
    val kind: MediaContainerKind,
) {
    fun encode(): String = CONTAINER_ROUTE_PREFIX + routeJson.encodeToString(this)

    companion object {
        fun from(container: MediaContainer): LibraryContainerRoute = LibraryContainerRoute(
            serverId = container.serverId,
            containerId = container.id,
            kind = container.kind,
        )

        fun decode(value: String): LibraryContainerRoute? {
            if (!value.startsWith(CONTAINER_ROUTE_PREFIX)) return null
            return runCatching {
                routeJson.decodeFromString<LibraryContainerRoute>(
                    value.removePrefix(CONTAINER_ROUTE_PREFIX),
                )
            }.getOrNull()
        }
    }
}

/** 查看全部 route for a server-paged BoxSet or Playlist directory. */
@Serializable
internal data class LibraryContainerDirectoryRoute(
    val serverId: String,
    val kind: MediaContainerKind,
) {
    fun encode(): String = CONTAINER_DIRECTORY_ROUTE_PREFIX + routeJson.encodeToString(this)

    companion object {
        fun decode(value: String): LibraryContainerDirectoryRoute? {
            if (!value.startsWith(CONTAINER_DIRECTORY_ROUTE_PREFIX)) return null
            return runCatching {
                routeJson.decodeFromString<LibraryContainerDirectoryRoute>(
                    value.removePrefix(CONTAINER_DIRECTORY_ROUTE_PREFIX),
                )
            }.getOrNull()
        }
    }
}

private val routeJson = Json { ignoreUnknownKeys = true }
