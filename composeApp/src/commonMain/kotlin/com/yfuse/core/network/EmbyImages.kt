package com.yfuse.core.network

import com.yfuse.core.model.MediaItem

/** Builds Emby image URLs. Emby image endpoints are public (no token needed). */
object EmbyImages {

    fun poster(baseUrl: String, item: MediaItem, maxHeight: Int = 450): String? =
        item.posterTag?.let { tag ->
            "${normalizeBaseUrl(baseUrl)}/Items/${item.posterItemId}/Images/Primary" +
                "?tag=$tag&maxHeight=$maxHeight&quality=90"
        }

    fun backdrop(baseUrl: String, item: MediaItem, maxWidth: Int = 1280): String? {
        val id = item.backdropItemId ?: return null
        val tag = item.backdropTag ?: return null
        return "${normalizeBaseUrl(baseUrl)}/Items/$id/Images/Backdrop/0?tag=$tag&maxWidth=$maxWidth&quality=85"
    }
}
