package com.yfuse.core.network

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person

/** Builds Emby image URLs. Emby image endpoints are public (no token needed). */
object EmbyImages {

    fun primary(baseUrl: String, itemId: String, tag: String?, maxHeight: Int = 450): String? {
        val t = tag ?: return null
        return "${normalizeBaseUrl(baseUrl)}/Items/$itemId/Images/Primary?tag=$t&maxHeight=$maxHeight&quality=90"
    }

    fun backdropOf(baseUrl: String, itemId: String, tag: String?, maxWidth: Int = 1280): String? {
        val t = tag ?: return null
        return "${normalizeBaseUrl(baseUrl)}/Items/$itemId/Images/Backdrop/0?tag=$t&maxWidth=$maxWidth&quality=85"
    }

    fun poster(baseUrl: String, item: MediaItem, maxHeight: Int = 450): String? =
        primary(baseUrl, item.posterItemId, item.posterTag, maxHeight)

    fun backdrop(baseUrl: String, item: MediaItem, maxWidth: Int = 1280): String? {
        val id = item.backdropItemId ?: return null
        return backdropOf(baseUrl, id, item.backdropTag, maxWidth)
    }

    fun poster(baseUrl: String, detail: MediaDetail, maxHeight: Int = 600): String? =
        primary(baseUrl, detail.posterItemId, detail.posterTag, maxHeight)

    fun backdrop(baseUrl: String, detail: MediaDetail, maxWidth: Int = 1280): String? =
        backdropOf(baseUrl, detail.backdropItemId, detail.backdropTag, maxWidth)

    fun avatar(baseUrl: String, person: Person, maxHeight: Int = 200): String? =
        primary(baseUrl, person.id, person.primaryImageTag, maxHeight)
}
