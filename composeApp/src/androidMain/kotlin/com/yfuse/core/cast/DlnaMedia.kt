package com.yfuse.core.cast

import java.net.URI

/**
 * What a DLNA renderer is told the file is.
 *
 * Renderers choose a demuxer from the MIME type in the DIDL `protocolInfo` and in the HTTP
 * response, and some refuse a file when the two disagree. Every address that was not HLS or
 * WebM used to be announced as `video/mp4`, so an MKV went to the television labelled as MP4.
 */
internal data class DlnaMediaFormat(
    val mimeType: String,
    /** Given to a relayed path for renderers that look at the URL rather than the header. */
    val extension: String,
    /** A static file that answers byte ranges. A live transcode or a playlist does not. */
    val byteSeekable: Boolean,
) {
    /**
     * The fourth `protocolInfo` field, also sent as `contentFeatures.dlna.org`. OP=01 offers byte
     * seeking only for a static file; the flags declare streaming and background transfer, connection
     * stalling and DLNA 1.5, the set most media servers publish.
     */
    val contentFeatures: String
        get() =
            if (byteSeekable) {
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$DLNA_FLAGS"
            } else {
                "DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=$DLNA_FLAGS"
            }

    val protocolInfo: String
        get() = "http-get:*:$mimeType:$contentFeatures"
}

/**
 * The format of [url]. Its own extension wins: a transcode URL names what the server will send.
 * [container] is the server's name for the original file, used when the URL has no extension
 * (Emby's `/Videos/{id}/stream?static=true`), so pass it only for the original file.
 */
internal fun dlnaMediaFormat(
    url: String,
    container: String? = null,
): DlnaMediaFormat {
    val path = runCatching { URI(url).path }.getOrNull() ?: url.substringBefore('?')
    val fromPath = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    if (fromPath == "m3u8") return DlnaMediaFormat(HLS_MIME_TYPE, "m3u8", byteSeekable = false)
    val extension =
        fromPath.takeIf { it in MIME_BY_EXTENSION }
            ?: container
                ?.split(',')
                ?.map { it.trim().lowercase().let { name -> CONTAINER_ALIASES[name] ?: name } }
                // Jellyfin names an MP4 "mov,mp4,m4a,3gp,3g2,mj2": the familiar name wins.
                ?.filter { it in MIME_BY_EXTENSION }
                ?.minByOrNull { MIME_BY_EXTENSION.keys.indexOf(it) }
            ?: DEFAULT_EXTENSION
    return DlnaMediaFormat(
        mimeType = MIME_BY_EXTENSION.getValue(extension),
        extension = extension,
        byteSeekable = !url.isLiveTranscodeUrl(),
    )
}

/**
 * DIDL-Lite for one video. `size` and `duration` are optional in the schema; renderers that read
 * them size their progress bar from them, so they are given whenever they are known.
 */
internal fun dlnaMetadata(
    url: String,
    title: String,
    format: DlnaMediaFormat,
    durationMs: Long? = null,
    sizeBytes: Long? = null,
): String =
    buildString {
        append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
        append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
        append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
        append("""<item id="0" parentID="0" restricted="1">""")
        append("<dc:title>${title.dlnaXmlEscape()}</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("""<res protocolInfo="${format.protocolInfo.dlnaXmlEscape()}"""")
        sizeBytes?.takeIf { it > 0L }?.let { append(""" size="$it"""") }
        durationMs?.takeIf { it > 0L }?.let { append(""" duration="${didlDuration(it)}"""") }
        append(">${url.dlnaXmlEscape()}</res>")
        append("</item></DIDL-Lite>")
    }

/** DIDL-Lite's `H+:MM:SS.FFF`; the hours are not padded. */
internal fun didlDuration(durationMs: Long): String {
    val total = durationMs.coerceAtLeast(0L)
    val hours = total / 3_600_000L
    val minutes = total % 3_600_000L / 60_000L
    val seconds = total % 60_000L / 1_000L
    val millis = total % 1_000L
    return "$hours:" + minutes.toString().padStart(2, '0') + ":" +
        seconds.toString().padStart(2, '0') + "." + millis.toString().padStart(3, '0')
}

internal fun String.dlnaXmlEscape(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

/** Emby, Jellyfin and Plex mark a server transcode in the query; an original file has none of these. */
private fun String.isLiveTranscodeUrl(): Boolean {
    val query =
        substringAfter('?', "")
            .split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=').lowercase()
                name.takeIf(String::isNotEmpty)?.let { it to pair.substringAfter('=', "").lowercase() }
            }
    return query.any { (name, value) ->
        when (name) {
            "static" -> value == "false"
            "transcodingprotocol", "transcodingcontainer" -> true
            "videocodec", "audiocodec" -> value != "copy"
            else -> false
        }
    }
}

private const val DLNA_FLAGS = "01700000000000000000000000000000"
private const val HLS_MIME_TYPE = "application/x-mpegURL"
private const val DEFAULT_EXTENSION = "mp4"

/** In order of preference when a container name lists several. */
private val MIME_BY_EXTENSION =
    linkedMapOf(
        "mkv" to "video/x-matroska",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "asf" to "video/x-ms-asf",
        "flv" to "video/x-flv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "3gp" to "video/3gpp",
        "ogv" to "video/ogg",
    )

/** ffprobe format names, which Jellyfin reports as the container. */
private val CONTAINER_ALIASES =
    mapOf(
        "matroska" to "mkv",
        "mpegts" to "ts",
        "mpeg2ts" to "ts",
        "bluray" to "m2ts",
        "mpegps" to "mpg",
    )
