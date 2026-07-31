package com.yfuse.core.network

import com.yfuse.core.model.PlaybackQuality

/** Builds Emby playback URLs. */
object EmbyStream {

    /**
     * Direct-play URL for a video item (serves the original file, no transcode).
     * The token is carried as `api_key` because that is what Emby's media
     * endpoints expect; the request only ever goes to the user's own server.
     */
    fun directPlay(
        baseUrl: String,
        itemId: String,
        token: String,
        mediaSourceId: String? = null,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/stream?static=true&api_key=$token" +
            mediaSourceParam(mediaSourceId, itemId)

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
        mediaSourceId: String? = null,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/master.m3u8" +
            "?api_key=$token" +
            "&MediaSourceId=${mediaSourceId ?: itemId}" +
            "&Context=Streaming" +
            "&TranscodingProtocol=hls" +
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            "&MaxWidth=$maxWidth" +
            "&VideoBitrate=$videoBitrate" +
            "&AudioBitrate=192000" +
            "&TranscodingMaxAudioChannels=2" +
            "&SegmentContainer=ts" +
            "&MinSegments=2" +
            "&BreakOnNonKeyFrames=true" +
            "&DeviceId=yfuse"

    /** Progressive H.264/AAC fallback when a server cannot produce a valid HLS manifest. */
    fun progressiveTranscode(
        baseUrl: String,
        itemId: String,
        token: String,
        maxWidth: Int = 1920,
        videoBitrate: Int = 6_000_000,
        mediaSourceId: String? = null,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/stream.mp4" +
            "?static=false" +
            "&api_key=$token" +
            "&MediaSourceId=${mediaSourceId ?: itemId}" +
            "&Context=Streaming" +
            "&Container=mp4" +
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            "&MaxWidth=$maxWidth" +
            "&VideoBitrate=$videoBitrate" +
            "&AudioBitrate=192000" +
            "&TranscodingMaxAudioChannels=2" +
            "&DeviceId=yfuse"

    /**
     * Names a specific file when the item has more than one.
     *
     * Left off entirely when it would only repeat the item id, which is what Emby assumes
     * anyway — keeping the common single-source URL byte-for-byte what it has always been.
     */
    private fun mediaSourceParam(mediaSourceId: String?, itemId: String): String =
        if (mediaSourceId == null || mediaSourceId == itemId) {
            ""
        } else {
            "&MediaSourceId=$mediaSourceId"
        }

    /** Rewrites the generated HLS cap without rebuilding the authenticated URL. */
    fun withQuality(url: String, quality: PlaybackQuality): String {
        val maxWidth = quality.maxWidth ?: return url
        val bitrate = quality.videoBitrate ?: return url
        return url
            .replace(Regex("([?&])MaxWidth=[^&]*"), "$1MaxWidth=$maxWidth")
            .replace(Regex("([?&])VideoBitrate=[^&]*"), "$1VideoBitrate=$bitrate")
    }
}
