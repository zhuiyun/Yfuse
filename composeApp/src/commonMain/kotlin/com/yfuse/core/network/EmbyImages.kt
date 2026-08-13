package com.yfuse.core.network

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person

/**
 * Builds Emby image URLs.
 *
 * Every builder takes the session's [accessToken] and carries it as `api_key`, the same
 * way [EmbyStream] does. A server that allows anonymous image access ignores the extra
 * query parameter; one that does not answers 401 without it, and the artwork silently
 * fails to load. Passing the token everywhere is the only shape that works on both, so
 * it is a plain parameter on every function rather than an opt-in some call sites
 * remember and others don't — which is exactly how the library, detail and search
 * screens ended up with half their posters blank while 播放记录 loaded fine.
 */
object EmbyImages {
    fun primary(
        baseUrl: String,
        itemId: String,
        tag: String?,
        maxHeight: Int = 450,
        accessToken: String? = null,
    ): String? {
        if (baseUrl.isBlank() || itemId.isBlank()) return null
        val tagQuery = tag?.let { "tag=$it&" }.orEmpty()
        return "${normalizeBaseUrl(baseUrl)}/Items/$itemId/Images/Primary?${tagQuery}maxHeight=$maxHeight&quality=90"
            .withToken(accessToken)
    }

    fun backdropOf(
        baseUrl: String,
        itemId: String,
        tag: String?,
        maxWidth: Int = 1280,
        accessToken: String? = null,
    ): String? =
        backdropAt(
            baseUrl = baseUrl,
            itemId = itemId,
            index = 0,
            tag = tag,
            maxWidth = maxWidth,
            accessToken = accessToken,
        )

    /**
     * One of an item's several backdrops. The index addresses the image and the tag is what
     * makes the URL cache-bust when the artwork is replaced, so they have to agree —
     * `tag` must be `BackdropImageTags[index]`.
     */
    fun backdropAt(
        baseUrl: String,
        itemId: String,
        index: Int,
        tag: String?,
        maxWidth: Int = 1280,
        accessToken: String? = null,
    ): String? {
        if (baseUrl.isBlank() || itemId.isBlank() || index < 0) return null
        val tagQuery = tag?.let { "tag=$it&" }.orEmpty()
        return "${normalizeBaseUrl(
            baseUrl,
        )}/Items/$itemId/Images/Backdrop/$index?${tagQuery}maxWidth=$maxWidth&quality=85"
            .withToken(accessToken)
    }

    fun poster(
        baseUrl: String,
        item: MediaItem,
        maxHeight: Int = 450,
        accessToken: String? = null,
    ): String? = primary(baseUrl, item.posterItemId, item.posterTag, maxHeight, accessToken)

    fun backdrop(
        baseUrl: String,
        item: MediaItem,
        maxWidth: Int = 1280,
        accessToken: String? = null,
    ): String? {
        val id = item.backdropItemId ?: return null
        return backdropOf(baseUrl, id, item.backdropTag, maxWidth, accessToken)
    }

    fun poster(
        baseUrl: String,
        detail: MediaDetail,
        maxHeight: Int = 600,
        accessToken: String? = null,
    ): String? = primary(baseUrl, detail.posterItemId, detail.posterTag, maxHeight, accessToken)

    fun backdrop(
        baseUrl: String,
        detail: MediaDetail,
        maxWidth: Int = 1280,
        accessToken: String? = null,
    ): String? = backdropOf(baseUrl, detail.backdropItemId, detail.backdropTag, maxWidth, accessToken)

    fun avatar(
        baseUrl: String,
        person: Person,
        maxHeight: Int = 200,
        accessToken: String? = null,
    ): String? = primary(baseUrl, person.id, person.primaryImageTag, maxHeight, accessToken)

    /** Every builder already ends in a query string, so the token is always an `&` away. */
    private fun String.withToken(accessToken: String?): String =
        accessToken?.takeIf { it.isNotBlank() }?.let { "$this&api_key=$it" } ?: this
}
