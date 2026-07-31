package com.yfuse.core.network

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person

/** Builds Emby image URLs. Emby image endpoints are public (no token needed). */
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
        val tokenQuery = accessToken?.takeIf { it.isNotBlank() }?.let { "&api_key=$it" }.orEmpty()
        return "${normalizeBaseUrl(baseUrl)}/Items/$itemId/Images/Primary?${tagQuery}maxHeight=$maxHeight&quality=90$tokenQuery"
    }

    fun backdropOf(baseUrl: String, itemId: String, tag: String?, maxWidth: Int = 1280): String? =
        backdropAt(baseUrl, itemId, index = 0, tag = tag, maxWidth = maxWidth)

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
    ): String? {
        if (baseUrl.isBlank() || itemId.isBlank() || index < 0) return null
        val tagQuery = tag?.let { "tag=$it&" }.orEmpty()
        return "${normalizeBaseUrl(baseUrl)}/Items/$itemId/Images/Backdrop/$index?${tagQuery}maxWidth=$maxWidth&quality=85"
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
        val raw = backdropOf(baseUrl, id, item.backdropTag, maxWidth) ?: return null
        return accessToken?.takeIf { it.isNotBlank() }?.let { "$raw&api_key=$it" } ?: raw
    }

    fun poster(baseUrl: String, detail: MediaDetail, maxHeight: Int = 600): String? =
        primary(baseUrl, detail.posterItemId, detail.posterTag, maxHeight)

    fun backdrop(baseUrl: String, detail: MediaDetail, maxWidth: Int = 1280): String? =
        backdropOf(baseUrl, detail.backdropItemId, detail.backdropTag, maxWidth)

    fun avatar(baseUrl: String, person: Person, maxHeight: Int = 200): String? =
        primary(baseUrl, person.id, person.primaryImageTag, maxHeight)
}
