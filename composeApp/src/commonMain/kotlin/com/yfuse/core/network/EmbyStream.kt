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

    /**
     * Server-side transcode to H.264/AAC over HLS. Needed for sources the
     * device cannot decode or render — notably Dolby Vision Profile 5, which
     * has no HDR10 fallback layer and plays with sound but no picture.
     */
    fun transcode(
        baseUrl: String,
        itemId: String,
        token: String,
        maxWidth: Int = 1920,
        videoBitrate: Int = 6_000_000,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/master.m3u8" +
            "?api_key=$token" +
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            "&MaxWidth=$maxWidth" +
            "&VideoBitrate=$videoBitrate" +
            "&AudioBitrate=192000" +
            "&TranscodingMaxAudioChannels=2" +
            "&DeviceId=yfuse"
}
