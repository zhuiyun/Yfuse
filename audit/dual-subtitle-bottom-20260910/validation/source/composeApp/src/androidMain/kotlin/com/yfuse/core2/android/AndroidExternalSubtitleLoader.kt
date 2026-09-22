package com.yfuse.core2.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YTextSubtitleParser
import com.yfuse.core2.subtitle.decodeExternalSubtitleText
import com.yfuse.core2.subtitle.externalTextSubtitleFormat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

internal data class AndroidLoadedExternalSubtitle(
    val track: YTrack,
    val cues: List<YSubtitleCue>,
)

/** Bounded sidecar loader. It never feeds external subtitle bytes into the video decoder. */
internal class AndroidExternalSubtitleLoader(
    private val resolveContent: () -> ContentResolver,
) {
    constructor(context: Context) : this({ context.applicationContext.contentResolver })

    suspend fun load(
        source: YExternalSubtitleSource,
        headers: Map<String, String>,
        trackId: String = EXTERNAL_SUBTITLE_TRACK_ID,
    ): AndroidLoadedExternalSubtitle {
        val loaded = read(source.uri, headers)
        val loadingContext = currentCoroutineContext()
        loadingContext.ensureActive()
        val text = decodeExternalSubtitleText(loaded.data)
        loadingContext.ensureActive()
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
        val parsed = YTextSubtitleParser.parse(text, format, loadingContext::ensureActive).cues
        loadingContext.ensureActive()
        require(parsed.isNotEmpty()) { "External subtitle contains no displayable cues" }
        val cues =
            if (format in setOf(YSubtitleFormat.Ass, YSubtitleFormat.Ssa) &&
                FfmpegNativeBridge.dynamicAssRendererAvailable
            ) {
                val script = YAssSubtitleSource(text.encodeToByteArray(), fullScript = true)
                // Keep the event intervals so idle gaps do not run a frame callback. The full script
                // remains shared and is parsed once by libass, retaining all styles and embedded fonts.
                parsed.map {
                    loadingContext.ensureActive()
                    it.copy(payload = YSubtitlePayload.AssEvent(script))
                }
            } else {
                parsed
            }
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

    private suspend fun read(
        uriString: String,
        headers: Map<String, String>,
    ): LoadedBytes =
        when (uriString.substringBefore(':').lowercase()) {
            "http", "https" -> readHttp(uriString, headers)
            "content", "file", "android.resource" -> {
                val uri = Uri.parse(uriString)
                val contentResolver = resolveContent()
                val signal = CancellationSignal()
                val input = AtomicReference<InputStream?>()
                withSubtitleReadCancellation(
                    close = {
                        signal.cancel()
                        runCatching { input.getAndSet(null)?.close() }
                    },
                ) {
                    // getType is a synchronous provider call without CancellationSignal. The
                    // declared format, URI suffix and content prefix already identify text cues.
                    val descriptor =
                        requireNotNull(contentResolver.openAssetFileDescriptor(uri, "r", signal)) {
                            "External subtitle source cannot be opened"
                        }
                    descriptor.use {
                        currentCoroutineContext().ensureActive()
                        descriptor.createInputStream().use { stream ->
                            input.set(stream)
                            currentCoroutineContext().ensureActive()
                            LoadedBytes(stream.readBoundedSubtitleBytes(), mimeType = null)
                        }
                    }
                }
            }
            else -> error("External subtitle source scheme is unsupported")
        }

    private suspend fun readHttp(
        uri: String,
        headers: Map<String, String>,
    ): LoadedBytes {
        val transport = AndroidHttpMediaTransport(followSafeRedirects = true, callTimeoutSeconds = 12L)
        return withSubtitleReadCancellation(close = { transport.close() }) {
            val protocol =
                if (uri.startsWith("https:", ignoreCase = true)) {
                    YSourceProtocol.Https
                } else {
                    YSourceProtocol.Http
                }
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
                currentCoroutineContext().ensureActive()
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
        }
    }
}

/** A sibling closes blocking IO as soon as its parent is cancelled, before the read can return. */
internal suspend fun <T> withSubtitleReadCancellation(
    close: suspend () -> Unit,
    read: suspend () -> T,
): T =
    withContext(Dispatchers.IO) {
        coroutineScope {
            val closer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { close() }
                    }
                }
            try {
                read().also { currentCoroutineContext().ensureActive() }
            } finally {
                withContext(NonCancellable) { closer.cancelAndJoin() }
            }
        }
    }

private data class LoadedBytes(
    val data: ByteArray,
    val mimeType: String?,
)

private suspend fun InputStream.readBoundedSubtitleBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
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
