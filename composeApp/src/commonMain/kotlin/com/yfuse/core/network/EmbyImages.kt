package com.yfuse.core.network

import com.yfuse.core.data.plexArtworkPath
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person
import io.ktor.http.encodeURLParameter

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
    /**
     * Emby re-encodes every image it serves, so the encoding is ours to ask for.
     *
     * WebP at a given quality runs roughly a quarter to a third smaller than the JPEG the
     * server returns by default, and Android has decoded it natively since long before this
     * app's minSdk 26. A home screen is almost entirely artwork, so this is the largest
     * single lever on what a self-hosted server has to push up a domestic uplink.
     *
     * `format` has been part of the Emby image API for a long time, and a server that does
     * not recognise it answers in its default encoding rather than failing — so the worst
     * case is losing the saving, not losing the picture. Should a deployment ever prove
     * otherwise, setting this to null is the entire rollback.
     */
    private val format: String? = "webp"

    /**
     * Posters are drawn at a few hundred pixels; 90 was buying detail no one can resolve at
     * that size. Backdrops fill the screen and keep a higher budget, because banding in a
     * large flat gradient is visible in a way poster grain is not.
     *
     * These are worth a pass on a real device: they are the one part of this file that is a
     * perceptual judgement rather than a mechanical one.
     */
    private const val POSTER_QUALITY = 85

    private const val BACKDROP_QUALITY = 85

    private val formatQuery = format?.let { "&format=$it" }.orEmpty()

    fun primary(
        baseUrl: String,
        itemId: String,
        tag: String?,
        maxHeight: Int = 450,
        accessToken: String? = null,
    ): String? {
        if (baseUrl.isBlank() || itemId.isBlank()) return null
        tag.plexArtworkPath()?.let { path ->
            // A 2:3 poster box. `minSize=1` fits the *smaller* edge, so a box wider than the
            // art made Plex return it at roughly three times the requested height.
            return plexImage(
                baseUrl,
                path,
                maxWidth = maxHeight * 2 / 3,
                maxHeight = maxHeight,
                accessToken = accessToken,
            )
        }
        val tagQuery = tag?.let { "tag=$it&" }.orEmpty()
        return "${normalizeBaseUrl(
            baseUrl,
        )}/Items/$itemId/Images/Primary?${tagQuery}maxHeight=$maxHeight&quality=$POSTER_QUALITY$formatQuery"
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
        tag.plexArtworkPath()?.let { path ->
            return plexImage(
                baseUrl,
                path,
                maxWidth = maxWidth,
                maxHeight = maxWidth * 9 / 16,
                accessToken = accessToken,
            )
        }
        val tagQuery = tag?.let { "tag=$it&" }.orEmpty()
        return "${normalizeBaseUrl(
            baseUrl,
        )}/Items/$itemId/Images/Backdrop/$index?${tagQuery}maxWidth=$maxWidth&quality=$BACKDROP_QUALITY$formatQuery"
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

    private fun plexImage(
        baseUrl: String,
        path: String,
        maxWidth: Int,
        maxHeight: Int,
        accessToken: String?,
    ): String {
        val token =
            accessToken
                ?.takeIf(String::isNotBlank)
                ?.let { "&X-Plex-Token=${it.encodeURLParameter()}" }
                .orEmpty()
        return "${normalizeBaseUrl(baseUrl)}/photo/:/transcode" +
            "?width=$maxWidth&height=$maxHeight&minSize=1&upscale=0" +
            "&url=${path.encodeURLParameter()}$token"
    }

    /** Every builder already ends in a query string, so the token is always an `&` away. */
    private fun String.withToken(accessToken: String?): String =
        accessToken?.takeIf { it.isNotBlank() }?.let { "$this&api_key=$it" } ?: this
}
