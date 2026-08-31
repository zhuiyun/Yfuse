package com.yfuse.core2.android

import android.content.Context
import android.net.Uri
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YTextSubtitleParser
import com.yfuse.core2.subtitle.decodeExternalSubtitleText
import com.yfuse.core2.subtitle.externalTextSubtitleFormat
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class AndroidLoadedExternalSubtitle(
    val track: YTrack,
    val cues: List<YSubtitleCue>,
)

/** Bounded sidecar loader. It never feeds external subtitle bytes into the video decoder. */
internal class AndroidExternalSubtitleLoader(
    context: Context,
) {
    private val contentResolver = context.applicationContext.contentResolver

    fun load(
        source: YExternalSubtitleSource,
        headers: Map<String, String>,
        trackId: String = EXTERNAL_SUBTITLE_TRACK_ID,
    ): AndroidLoadedExternalSubtitle {
        val loaded = read(source.uri, headers)
        val text = decodeExternalSubtitleText(loaded.data)
        val format =
            source.format
                ?: externalTextSubtitleFormat(
                    uri = source.uri,
                    mimeType = loaded.mimeType,
                    contentPrefix = text.take(FORMAT_SNIFF_CHARACTERS),
                )
                ?: error("External subtitle format is unsupported")
        require(format.standaloneTextSupported) {
            "External subtitle format is unsupported"
        }
        val cues = YTextSubtitleParser.parse(text, format).cues
        require(cues.isNotEmpty()) { "External subtitle contains no displayable cues" }
        return AndroidLoadedExternalSubtitle(
            track =
                YTrack(
                    id = trackId,
                    type = YTrackType.Subtitle,
                    label = source.language?.takeIf(String::isNotBlank) ?: "External subtitle",
                    language = source.language,
                    codec = format.externalMimeType(),
                    selected = true,
                ),
            cues = cues,
        )
    }

    private fun read(
        uriString: String,
        headers: Map<String, String>,
    ): LoadedBytes {
        val uri = Uri.parse(uriString)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> readHttp(uriString, headers)
            "content", "file", "android.resource" -> {
                val mimeType = contentResolver.getType(uri)
                val data =
                    requireNotNull(contentResolver.openInputStream(uri)) {
                        "External subtitle source cannot be opened"
                    }.use(InputStream::readBoundedSubtitleBytes)
                LoadedBytes(data, mimeType)
            }
            else -> error("External subtitle source scheme is unsupported")
        }
    }

    private fun readHttp(
        uri: String,
        headers: Map<String, String>,
    ): LoadedBytes =
        runBlocking {
            val protocol =
                if (Uri.parse(uri).scheme.equals("https", ignoreCase = true)) {
                    YSourceProtocol.Https
                } else {
                    YSourceProtocol.Http
                }
            val transport = AndroidHttpMediaTransport(followSafeRedirects = true)
            try {
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = uri,
                            protocol = protocol,
                            headers = headers,
                        ),
                    )
                require(response.statusCode in 200..299) { "External subtitle request failed" }
                response.contentLength?.let { declaredLength ->
                    require(declaredLength <= MAX_EXTERNAL_SUBTITLE_BYTES) {
                        "External subtitle exceeds the size limit"
                    }
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0
                while (true) {
                    val count = transport.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= MAX_EXTERNAL_SUBTITLE_BYTES) {
                        "External subtitle exceeds the size limit"
                    }
                    output.write(buffer, 0, count)
                }
                LoadedBytes(data = output.toByteArray(), mimeType = null)
            } finally {
                transport.close()
            }
        }
}

private data class LoadedBytes(
    val data: ByteArray,
    val mimeType: String?,
)

private fun InputStream.readBoundedSubtitleBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= MAX_EXTERNAL_SUBTITLE_BYTES) { "External subtitle exceeds the size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun YSubtitleFormat.externalMimeType(): String =
    when (this) {
        YSubtitleFormat.Srt -> "application/x-subrip"
        YSubtitleFormat.WebVtt -> "text/vtt"
        YSubtitleFormat.Ass -> "text/x-ass"
        YSubtitleFormat.Ssa -> "text/x-ssa"
        else -> error("External subtitle format is unsupported")
    }

internal const val EXTERNAL_SUBTITLE_TRACK_ID = "subtitle:external"
internal const val EXTERNAL_SUBTITLE_TRACK_PREFIX = "$EXTERNAL_SUBTITLE_TRACK_ID:"

internal fun externalSubtitleTrackId(index: Int): String {
    require(index >= 0)
    return "$EXTERNAL_SUBTITLE_TRACK_PREFIX$index"
}
private const val FORMAT_SNIFF_CHARACTERS = 4_096
private const val MAX_EXTERNAL_SUBTITLE_BYTES = 8 * 1024 * 1024
private const val READ_BUFFER_BYTES = 16 * 1024
