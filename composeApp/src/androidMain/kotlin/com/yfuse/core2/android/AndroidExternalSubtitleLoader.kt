package com.yfuse.core2.android

import android.content.Context
import android.net.Uri
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YTextSubtitleParser
import com.yfuse.core2.subtitle.decodeExternalSubtitleText
import com.yfuse.core2.subtitle.externalTextSubtitleFormat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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
                    id = EXTERNAL_SUBTITLE_TRACK_ID,
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
    ): LoadedBytes {
        val connection = URL(uri).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = HTTP_READ_TIMEOUT_MS
            headers.forEach(connection::setRequestProperty)
            connection.connect()
            require(connection.responseCode in 200..299) { "External subtitle request failed" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0L || declaredLength <= MAX_EXTERNAL_SUBTITLE_BYTES) {
                "External subtitle exceeds the size limit"
            }
            LoadedBytes(
                data = connection.inputStream.use(InputStream::readBoundedSubtitleBytes),
                mimeType = connection.contentType,
            )
        } finally {
            connection.disconnect()
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
private const val FORMAT_SNIFF_CHARACTERS = 4_096
private const val MAX_EXTERNAL_SUBTITLE_BYTES = 8 * 1024 * 1024
private const val READ_BUFFER_BYTES = 16 * 1024
private const val HTTP_CONNECT_TIMEOUT_MS = 10_000
private const val HTTP_READ_TIMEOUT_MS = 20_000
