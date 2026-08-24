package com.yfuse.core.network

import com.yfuse.deviceId
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlin.random.Random

/**
 * Every address one file can be played from, plus the session id all of them carry.
 *
 * They travel together because they have to agree: the transcode target is derived from the
 * same source figures, and the session id has to be identical across all three or a fallback
 * from one to another would look like a different playback to the server.
 */
data class StreamUrls(
    val direct: String,
    val transcode: String,
    val progressiveTranscode: String,
    val playSessionId: String,
)

private data class NegotiatedStreamOrigin(
    val protocol: URLProtocol,
    val host: String,
    val port: Int,
)

private fun Url.negotiatedStreamOrigin(): NegotiatedStreamOrigin? =
    takeIf { protocol.name.lowercase() in setOf("http", "https") }
        ?.let { url ->
            NegotiatedStreamOrigin(
                protocol = url.protocol,
                host = url.host.trimEnd('.').lowercase(),
                port = if (url.specifiedPort == 0) url.protocol.defaultPort else url.specifiedPort,
            )
        }

private fun String.negotiatedStreamOrigin(): NegotiatedStreamOrigin? =
    runCatching { Url(this) }.getOrNull()?.negotiatedStreamOrigin()

private fun Url.transportValidationEndpoint(): String {
    val endpointHost = if (':' in host) "[$host]" else host
    val endpointPort = specifiedPort.takeIf { it != 0 }?.let { ":$it" }.orEmpty()
    return "${protocol.name}://$endpointHost$endpointPort"
}

/** Builds Emby playback URLs. */
object EmbyStream {
    /** External subtitle stream. Tokens are created at the last responsible moment. */
    fun subtitle(
        baseUrl: String,
        itemId: String,
        mediaSourceId: String,
        streamIndex: Int,
        token: String,
        format: String = "srt",
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/$mediaSourceId/Subtitles/$streamIndex/Stream.$format" +
            "?api_key=${token.queryValue()}"

    fun trickplayTilePattern(
        baseUrl: String,
        itemId: String,
        mediaSourceId: String,
        width: Int,
        token: String,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/Trickplay/$width/{index}.jpg" +
            "?MediaSourceId=${mediaSourceId.queryValue()}&api_key=${token.queryValue()}"

    /**
     * Resolves a URL returned by PlaybackInfo and completes its authentication.
     * Relative and same-origin absolute URLs remain authenticated against the saved server.
     * A server may legitimately negotiate an absolute CDN URL, but appending the Emby credential
     * or internal session identifiers to that different authority would disclose them and can
     * also invalidate a signed CDN URL, so a cross-origin address is returned unmodified.
     */
    fun negotiatedUrl(
        baseUrl: String,
        rawUrl: String,
        token: String,
        playSessionId: String,
        addApiKey: Boolean = true,
        localCleartextConfirmed: Boolean = false,
    ): String? {
        val trimmedUrl = rawUrl.trim()
        val absoluteWebUrl =
            trimmedUrl
                .substringBefore("://", missingDelimiterValue = "")
                .lowercase() in setOf("http", "https")
        val parsedAbsoluteUrl =
            if (absoluteWebUrl) {
                runCatching { Url(trimmedUrl) }
                    .getOrNull()
                    ?.takeIf {
                        it.negotiatedStreamOrigin() != null &&
                            it.user == null &&
                            it.password == null &&
                            it.fragment.isEmpty()
                    } ?: return null
            } else {
                null
            }
        val value =
            if (parsedAbsoluteUrl != null) {
                // Validate only the authority. PlaybackInfo stream URLs legitimately carry query
                // parameters, while the endpoint policy owns the unchanged HTTP/HTTPS decision.
                val validation =
                    validateEmbyServerEndpoint(
                        parsedAbsoluteUrl.transportValidationEndpoint(),
                        localCleartextConfirmed,
                    )
                trimmedUrl.takeIf { validation.allowed } ?: return null
            } else {
                "${normalizeBaseUrl(baseUrl)}/${trimmedUrl.trimStart('/')}"
            }
        val targetOrigin = parsedAbsoluteUrl?.negotiatedStreamOrigin() ?: value.negotiatedStreamOrigin()
        val serverOrigin = normalizeBaseUrl(baseUrl).negotiatedStreamOrigin()
        val sameServerOrigin = !absoluteWebUrl || (targetOrigin != null && targetOrigin == serverOrigin)
        if (!sameServerOrigin) return value

        var authenticatedValue = value
        if (
            addApiKey &&
            !authenticatedValue.hasQueryParameter("api_key") &&
            !authenticatedValue.hasQueryParameter("X-Emby-Token")
        ) {
            authenticatedValue = authenticatedValue.withQueryParameter("api_key", token.queryValue())
        }
        if (!authenticatedValue.hasQueryParameter("DeviceId")) {
            authenticatedValue = authenticatedValue.withQueryParameter("DeviceId", deviceId().queryValue())
        }
        if (playSessionId.isNotBlank() && !authenticatedValue.hasQueryParameter("PlaySessionId")) {
            authenticatedValue = authenticatedValue.withQueryParameter("PlaySessionId", playSessionId.queryValue())
        }
        return authenticatedValue
    }

    /**
     * The addresses for one file, with the transcode ladder aimed at the source.
     *
     * The single entry point for building a playable entry. Callers used to assemble the
     * triple themselves, and the episode-polling path in the player assembled it with the
     * bare defaults — a fixed 1080p/6 Mbps ceiling and no `MediaSourceId` — so an episode
     * that arrived by polling was addressed differently from the identical episode the
     * detail page had opened.
     */
    fun streamUrls(
        baseUrl: String,
        itemId: String,
        token: String,
        mediaSourceId: String? = null,
        sourceWidth: Int? = null,
        sourceBitrateBps: Int? = null,
    ): StreamUrls {
        val (maxWidth, videoBitrate) = transcodeTarget(sourceWidth, sourceBitrateBps)
        val session = newPlaySessionId()
        return StreamUrls(
            direct = directPlay(baseUrl, itemId, token, mediaSourceId, session),
            transcode =
                transcode(
                    baseUrl = baseUrl,
                    itemId = itemId,
                    token = token,
                    maxWidth = maxWidth,
                    videoBitrate = videoBitrate,
                    mediaSourceId = mediaSourceId,
                    playSessionId = session,
                ),
            progressiveTranscode =
                progressiveTranscode(
                    baseUrl = baseUrl,
                    itemId = itemId,
                    token = token,
                    maxWidth = maxWidth,
                    videoBitrate = videoBitrate,
                    mediaSourceId = mediaSourceId,
                    playSessionId = session,
                ),
            playSessionId = session,
        )
    }

    /**
     * A fresh play-session id, to be shared by a stream URL and the
     * `/Sessions/Playing` reports that describe the same playback.
     *
     * Emby identifies an in-flight transcode by the pair (`DeviceId`, `PlaySessionId`).
     * Both therefore have to appear on the stream URL *and* on the reports, or
     * `Playing/Stopped` has no way to name the ffmpeg job it is supposed to end — which is
     * how orphaned encodings pile up until the server starts refusing new ones with a 4xx.
     */
    fun newPlaySessionId(): String =
        buildString {
            // The video-streaming contract calls this an alpha-numeric value. Some older or
            // proxied Emby installations validate that literally and reject punctuation with
            // HTTP 400, so keep the useful client prefix without the old hyphen.
            append("yfuse")
            repeat(24) { append("0123456789abcdef"[Random.nextInt(16)]) }
        }

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
        playSessionId: String? = null,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/stream?static=true&api_key=${token.queryValue()}" +
            mediaSourceParam(mediaSourceId, itemId) +
            sessionParams(playSessionId)

    /**
     * The width and bitrate a transcode of this source should aim at.
     *
     * Transcoding happens because playback *failed*, not because the file was too big.
     * A 4K Dolby Vision remux that falls back to a fixed 1080p / 6 Mbps loses far more
     * than the Dolby layer that made the fallback necessary — and the ceiling was fixed
     * at a time when the setting that would have raised it had already been withdrawn.
     *
     * So: follow the source, capped at 4K, never upscaled, and never below the old
     * default — a server that could manage 1080p before can still manage it. The bitrate
     * follows the width rather than the source's own, because H.264 needs more bits than
     * the HEVC it is usually replacing and the source figure would starve it.
     */
    fun transcodeTarget(
        sourceWidth: Int?,
        sourceBitrateBps: Int?,
    ): Pair<Int, Int> {
        val width = (sourceWidth ?: 0).coerceIn(1920, 3840)
        val bitrate =
            when {
                width > 2560 -> 24_000_000
                width > 1920 -> 16_000_000
                else -> 8_000_000
            }
        // A source that is genuinely thinner than the ladder does not need padding out.
        val capped = sourceBitrateBps?.takeIf { it in 1..bitrate } ?: bitrate
        return width to capped
    }

    /**
     * Server-side compatibility transcode to H.264/AAC over HLS.
     *
     * This path is entered only after the original stream proved unusable. It must therefore never
     * allow Emby/Jellyfin's automatic stream-copy optimization to preserve the very codec/profile
     * that forced the fallback. In particular, copying Dolby Vision Profile 5 into TS or progressive
     * MP4 simply moves the same green/magenta failure to another URL while claiming it was transcoded.
     */
    fun transcode(
        baseUrl: String,
        itemId: String,
        token: String,
        maxWidth: Int = 1920,
        videoBitrate: Int = 6_000_000,
        mediaSourceId: String? = null,
        playSessionId: String? = null,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/master.m3u8" +
            "?api_key=${token.queryValue()}" +
            "&MediaSourceId=${(mediaSourceId ?: itemId).queryValue()}" +
            "&Context=Streaming" +
            // Keep both names for Emby/Jellyfin generations that key off different fields.
            "&Container=ts" +
            "&TranscodingContainer=ts" +
            "&SegmentContainer=ts" +
            "&TranscodingProtocol=hls" +
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            // A fallback is a compatibility encode, not another opportunity to stream-copy the
            // source. These flags are deliberately explicit because P5 can otherwise survive the
            // request untouched even when VideoCodec=h264 was supplied.
            "&EnableAutoStreamCopy=false" +
            "&AllowVideoStreamCopy=false" +
            "&AllowAudioStreamCopy=false" +
            "&RequireAvc=true" +
            "&MaxWidth=$maxWidth" +
            "&VideoBitrate=$videoBitrate" +
            "&AudioBitrate=192000" +
            "&MaxAudioChannels=2" +
            // Older Emby/Jellyfin derivatives generated this legacy name themselves.
            // Supplying both is harmless on current servers and keeps those proxies working.
            "&TranscodingMaxAudioChannels=2" +
            "&MinSegments=2" +
            "&BreakOnNonKeyFrames=true" +
            sessionParams(playSessionId)

    /** Progressive H.264/AAC compatibility fallback when a server cannot produce valid HLS. */
    fun progressiveTranscode(
        baseUrl: String,
        itemId: String,
        token: String,
        maxWidth: Int = 1920,
        videoBitrate: Int = 6_000_000,
        mediaSourceId: String? = null,
        playSessionId: String? = null,
    ): String =
        "${normalizeBaseUrl(baseUrl)}/Videos/$itemId/stream.mp4" +
            "?static=false" +
            "&api_key=${token.queryValue()}" +
            "&MediaSourceId=${(mediaSourceId ?: itemId).queryValue()}" +
            "&Context=Streaming" +
            "&Container=mp4" +
            "&TranscodingContainer=mp4" +
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            "&EnableAutoStreamCopy=false" +
            "&AllowVideoStreamCopy=false" +
            "&AllowAudioStreamCopy=false" +
            "&RequireAvc=true" +
            "&MaxWidth=$maxWidth" +
            "&VideoBitrate=$videoBitrate" +
            "&AudioBitrate=192000" +
            "&MaxAudioChannels=2" +
            "&TranscodingMaxAudioChannels=2" +
            sessionParams(playSessionId)

    /**
     * Makes a same-server PlaybackInfo HLS URL honor Yfuse's compatibility contract.
     *
     * Some Emby derivatives return a transcode URL that still copies TrueHD/EAC3/DTS or omits
     * the segment container. Cross-origin CDN URLs are left byte-for-byte intact because they may
     * be signed; same-origin URLs are ordinary Emby endpoints and can safely receive these hints.
     */
    fun compatibleHlsTranscodeUrl(
        baseUrl: String,
        url: String,
    ): String {
        if (url.isBlank()) return url
        val serverOrigin = normalizeBaseUrl(baseUrl).negotiatedStreamOrigin()
        val targetOrigin = url.negotiatedStreamOrigin()
        if (serverOrigin == null || targetOrigin == null || serverOrigin != targetOrigin) return url
        return url
            .withOrReplaceQueryParameter("Context", "Streaming")
            .withOrReplaceQueryParameter("Container", "ts")
            .withOrReplaceQueryParameter("TranscodingProtocol", "hls")
            .withOrReplaceQueryParameter("SegmentContainer", "ts")
            .withOrReplaceQueryParameter("VideoCodec", "h264")
            .withOrReplaceQueryParameter("AudioCodec", "aac")
            .withOrReplaceQueryParameter("AudioBitrate", "192000")
            .withOrReplaceQueryParameter("MaxAudioChannels", "2")
            .withOrReplaceQueryParameter("TranscodingMaxAudioChannels", "2")
    }

    /**
     * Identifies who is asking and which playback this is.
     *
     * `DeviceId` was the literal string `yfuse` for every install of the app, so a server
     * could not tell two of the user's own devices apart — never mind two users. It is now
     * this install's persisted id, the same one the `X-Emby-Authorization` header carries.
     *
     * `PlaySessionId` is omitted rather than invented when absent: a wrong id is worse than
     * none, because `Playing/Stopped` would then end somebody else's encoding.
     */
    private fun sessionParams(playSessionId: String?): String =
        "&DeviceId=${deviceId().queryValue()}" +
            playSessionId
                ?.takeIf { it.isNotBlank() }
                ?.let { "&PlaySessionId=${it.queryValue()}" }
                .orEmpty()

    /**
     * Names a specific file when the item has more than one.
     *
     * Left off entirely when it would only repeat the item id, which is what Emby assumes
     * anyway — keeping the common single-source URL byte-for-byte what it has always been.
     */
    private fun mediaSourceParam(
        mediaSourceId: String?,
        itemId: String,
    ): String =
        if (mediaSourceId == null || mediaSourceId == itemId) {
            ""
        } else {
            "&MediaSourceId=${mediaSourceId.queryValue()}"
        }

    private fun String.queryValue(): String = encodeURLParameter()

    private fun String.hasQueryParameter(name: String): Boolean =
        Regex("(?:[?&])${Regex.escape(name)}=", RegexOption.IGNORE_CASE).containsMatchIn(this)

    private fun String.withQueryParameter(
        name: String,
        encodedValue: String,
    ): String = "$this${if ('?' in this) '&' else '?'}$name=$encodedValue"

    private fun String.withOrReplaceQueryParameter(
        name: String,
        encodedValue: String,
    ): String {
        val parameter = Regex("([?&])${Regex.escape(name)}=[^&]*", RegexOption.IGNORE_CASE)
        return if (parameter.containsMatchIn(this)) {
            replace(parameter, "$1$name=$encodedValue")
        } else {
            withQueryParameter(name, encodedValue)
        }
    }

}
