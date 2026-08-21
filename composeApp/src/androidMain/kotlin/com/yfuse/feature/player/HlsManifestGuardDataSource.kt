package com.yfuse.feature.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Raised before Media3's HLS parser sees a response that is obviously not an HLS manifest.
 *
 * A number of Emby/Jellyfin derivatives answer a failed `/master.m3u8` request with HTTP 200 and an
 * HTML or JSON error body. Media3 can only report "manifest malformed" in that case, which hides the
 * real server failure and makes a retry pointlessly request the same invalid body. This exception
 * preserves a redacted preview and content type so the fallback chain can explain what happened.
 */
internal class InvalidHlsManifestResponseException(
    val contentType: String?,
    val redactedPreview: String,
) : IOException(
        buildString {
            append("HLS endpoint did not return an #EXTM3U manifest")
            contentType?.takeIf(String::isNotBlank)?.let { append(" (content-type=$it)") }
            redactedPreview.takeIf(String::isNotBlank)?.let { append(": $it") }
        },
    )

/** Validates only `.m3u8` response bodies and otherwise behaves exactly like [upstreamFactory]. */
@UnstableApi
internal class HlsManifestGuardDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = HlsManifestGuardDataSource(upstreamFactory.createDataSource())
}

@UnstableApi
private class HlsManifestGuardDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var shouldValidate = false
    private var validated = false
    private var replay = ByteArray(0)
    private var replayOffset = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        shouldValidate = dataSpec.uri.isHlsManifestUri()
        validated = !shouldValidate
        replay = ByteArray(0)
        replayOffset = 0
        return upstream.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (replayOffset < replay.size) return copyReplay(buffer, offset, length)
        if (validated) return upstream.read(buffer, offset, length)

        val probe = ByteArray(HLS_SIGNATURE_PROBE_BYTES)
        var count = 0
        while (count < probe.size) {
            val read = upstream.read(probe, count, probe.size - count)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read <= 0) break
            count += read
            if (count >= HLS_MIN_SIGNATURE_BYTES && probe.copyOf(count).hasHlsManifestSignature()) break
        }

        if (count == 0) return C.RESULT_END_OF_INPUT
        val firstBytes = probe.copyOf(count)
        if (!firstBytes.hasHlsManifestSignature()) {
            throw InvalidHlsManifestResponseException(
                contentType = upstream.responseHeaders.firstHeaderValue("Content-Type"),
                redactedPreview = firstBytes.redactedManifestPreview(),
            )
        }

        replay = firstBytes
        replayOffset = 0
        validated = true
        return copyReplay(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        shouldValidate = false
        validated = false
        replay = ByteArray(0)
        replayOffset = 0
        upstream.close()
    }

    private fun copyReplay(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = minOf(length, replay.size - replayOffset)
        replay.copyInto(buffer, destinationOffset = offset, startIndex = replayOffset, endIndex = replayOffset + count)
        replayOffset += count
        if (replayOffset >= replay.size) {
            replay = ByteArray(0)
            replayOffset = 0
        }
        return count
    }
}

internal fun Uri.isHlsManifestUri(): Boolean = toString().isHlsManifestUrl()

/** Pure helper so local unit tests do not depend on Android's framework Uri implementation. */
internal fun String.isHlsManifestUrl(): Boolean =
    substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('/')
        .endsWith(".m3u8", ignoreCase = true)

internal fun ByteArray.hasHlsManifestSignature(): Boolean {
    if (isEmpty()) return false
    val text = toString(Charsets.UTF_8).removePrefix("\uFEFF")
    return text.trimStart(' ', '\t', '\r', '\n').startsWith("#EXTM3U")
}

private fun Map<String, List<String>>.firstHeaderValue(name: String): String? =
    entries
        .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun ByteArray.redactedManifestPreview(): String =
    toString(Charsets.UTF_8)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(HLS_PREVIEW_CHARS)
        .replace(
            Regex("(?i)(api_key|x-emby-token|access_token|token)=([^&\\s\\\"']+)"),
            "$1=<redacted>",
        )

private const val HLS_SIGNATURE_PROBE_BYTES = 1_024
private const val HLS_MIN_SIGNATURE_BYTES = 7
private const val HLS_PREVIEW_CHARS = 240
