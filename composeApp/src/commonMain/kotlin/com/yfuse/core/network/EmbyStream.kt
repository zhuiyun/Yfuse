package com.yfuse.core.network

/** Builds Emby playback URLs. */
object EmbyStream {

    /**
     * Direct-play URL for a video item (serves the original file, no transcode).
     * The token is carried as `api_key` because that is what Emby's media
     * endpoints expect; the request only ever goes to the user's own server.
     */
    fun directPlay(baseUrl: String, itemId: String, token: String): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/stream?static=true&api_key=$token"
}
