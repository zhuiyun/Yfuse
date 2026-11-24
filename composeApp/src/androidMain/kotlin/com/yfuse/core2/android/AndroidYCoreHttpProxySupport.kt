package com.yfuse.core2.android

import com.yfuse.core2.adaptive.YAdaptiveVariant
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFailureKind
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class YAdaptivePlaybackFeedback(
    val bufferedDurationUs: Long,
    val playing: Boolean,
    val speed: Float,
    val generation: Long,
)

/**
 * ABR buffer estimate backed by player truth when it is available.
 *
 * The manifest proxy used to drain a synthetic ten-second startup value against wall clock time.
 * That made pause, seek, and non-1x playback select the wrong rendition. Feedback replaces the
 * synthetic value; between reports the model advances only while playback is actually consuming.
 */
internal class YAdaptiveBufferModel(
    initialBufferedDurationUs: Long = STARTUP_BUFFER_US,
) {
    private var bufferedDurationUs = initialBufferedDurationUs.coerceIn(0L, MAX_ABR_BUFFER_US)
    private var updatedAtNs = System.nanoTime()
    private var playing = true
    private var speed = 1f
    private var feedbackGeneration = Long.MIN_VALUE
    private var lastFeedbackRecordedAtNs = Long.MIN_VALUE

    @Synchronized
    fun applyFeedback(
        feedback: YAdaptivePlaybackFeedback,
        recordedAtNs: Long,
    ) {
        if (
            feedback.generation < feedbackGeneration ||
            (feedback.generation == feedbackGeneration && recordedAtNs <= lastFeedbackRecordedAtNs)
        ) {
            return
        }
        feedbackGeneration = feedback.generation
        lastFeedbackRecordedAtNs = recordedAtNs
        bufferedDurationUs = feedback.bufferedDurationUs.coerceIn(0L, MAX_ABR_BUFFER_US)
        playing = feedback.playing
        speed = feedback.speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        updatedAtNs = recordedAtNs
    }

    @Synchronized
    fun estimate(nowNs: Long = System.nanoTime()): Long {
        drain(nowNs)
        return bufferedDurationUs
    }

    @Synchronized
    fun completeSegment(
        durationUs: Long,
        contiguous: Boolean,
        nowNs: Long = System.nanoTime(),
    ) {
        drain(nowNs)
        // Fresh player feedback already includes bytes arriving through this proxy. Adding the
        // segment again would double count it. The wall-clock fallback remains for startup and for
        // callers that cannot provide feedback.
        if (
            lastFeedbackRecordedAtNs != Long.MIN_VALUE &&
            nowNs - lastFeedbackRecordedAtNs <= ABR_FEEDBACK_STALE_NS
        ) {
            return
        }
        bufferedDurationUs =
            if (contiguous) {
                (bufferedDurationUs + durationUs).coerceAtMost(MAX_ABR_BUFFER_US)
            } else {
                durationUs.coerceIn(0L, MAX_ABR_BUFFER_US)
            }
    }

    private fun drain(nowNs: Long) {
        val elapsedUs = (nowNs - updatedAtNs).coerceAtLeast(0L) / NANOS_PER_MICROSECOND
        if (playing && elapsedUs > 0L) {
            val consumedUs = (elapsedUs.toDouble() * speed.toDouble()).toLong()
            bufferedDurationUs = (bufferedDurationUs - consumedUs).coerceAtLeast(0L)
        }
        updatedAtNs = nowNs
    }
}

internal data class TimedAdaptivePlaybackFeedback(
    val value: YAdaptivePlaybackFeedback,
    val recordedAtNs: Long,
)

internal data class YCoreHttpByteRange(
    val startInclusive: Long,
    val endInclusive: Long?,
)

internal data class YCoreProxyUpstreamRequestContext(
    val headers: Map<String, String>,
    val credentials: YTransportCredentials?,
)

internal fun yCoreProxyUpstreamRequestContext(
    upstreamHeaders: Map<String, String>,
    configuredUserAgent: String,
    credentials: YTransportCredentials?,
): YCoreProxyUpstreamRequestContext {
    val headers =
        if (upstreamHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            upstreamHeaders
        } else {
            configuredUserAgent
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let { upstreamHeaders + ("User-Agent" to it) }
                ?: upstreamHeaders
        }
    return YCoreProxyUpstreamRequestContext(headers = headers, credentials = credentials)
}

internal fun parseYCoreHttpByteRange(value: String?): YCoreHttpByteRange? {
    val match = value?.trim()?.let(HTTP_BYTE_RANGE::matchEntire) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val endText = match.groupValues[2]
    val end = if (endText.isEmpty()) null else endText.toLongOrNull() ?: return null
    if (end != null && end < start) return null
    return YCoreHttpByteRange(startInclusive = start, endInclusive = end)
}

/**
 * Carries only RFC 8216 low-latency reload coordinates from the loopback client to the origin.
 * Authentication and arbitrary local query parameters must never be able to cross this boundary.
 */
internal fun mergeYCoreHlsReloadQuery(
    upstreamUri: String,
    localRawQuery: String,
): String {
    val accepted = linkedMapOf<String, String>()
    localRawQuery
        .split('&')
        .asSequence()
        .filter(String::isNotBlank)
        .take(MAX_HLS_RELOAD_QUERY_PARAMETERS + 1)
        .forEach { parameter ->
            val rawName = parameter.substringBefore('=')
            val rawValue = parameter.substringAfter('=', missingDelimiterValue = "")
            val canonicalName = HLS_RELOAD_QUERY_NAMES[rawName.lowercase()] ?: return@forEach
            val valid =
                when (canonicalName) {
                    "_HLS_msn" -> rawValue.toLongOrNull()?.let { it >= 0L } == true
                    "_HLS_part" -> rawValue.toIntOrNull()?.let { it >= 0 } == true
                    "_HLS_skip" ->
                        rawValue.equals("YES", ignoreCase = true) || rawValue.equals("v2", ignoreCase = true)
                    else -> false
                }
            if (valid) accepted[canonicalName] = rawValue
        }
    if (accepted.isEmpty()) return upstreamUri

    val fragment = upstreamUri.substringAfter('#', missingDelimiterValue = "")
    val withoutFragment = upstreamUri.substringBefore('#')
    val path = withoutFragment.substringBefore('?')
    val retained =
        withoutFragment
            .substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter(String::isNotBlank)
            .filterNot { parameter -> parameter.substringBefore('=').lowercase() in HLS_RELOAD_QUERY_NAMES }
    val mergedQuery = (retained + accepted.map { (name, value) -> "$name=$value" }).joinToString("&")
    return buildString {
        append(path)
        if (mergedQuery.isNotEmpty()) append('?').append(mergedQuery)
        if (fragment.isNotEmpty()) append('#').append(fragment)
    }
}

internal fun String.isHlsManifestUri(): Boolean =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .lowercase()
            .endsWith(".m3u8")
    }.getOrDefault(false)

internal fun String.isDashManifestUri(): Boolean =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .lowercase()
            .endsWith(".mpd")
    }.getOrDefault(false)

internal fun String.hasSeparateYCoreHlsRenditions(): Boolean =
    lineSequence().any { line ->
        val normalized = line.trim().uppercase()
        normalized.startsWith("#EXT-X-MEDIA:") ||
            normalized.startsWith("#EXT-X-STREAM-INF:") &&
            ("AUDIO=" in normalized || "VIDEO=" in normalized || "SUBTITLES=" in normalized)
    }

/**
 * A session key for sample encryption, which is DRM that only the native DRM route opens. An
 * AES-128 session key only lets a client fetch, early, the keys its media playlists name anyway,
 * and FFmpeg decrypts those segments itself; refusing it refused clear AES-128 streams.
 */
internal fun String.hasDrmHlsSessionKey(): Boolean =
    lineSequence().any { line ->
        val normalized = line.trim().uppercase()
        normalized.startsWith("#EXT-X-SESSION-KEY:") && !normalized.contains("METHOD=AES-128")
    }

internal fun String.hasLowLatencyHlsParts(): Boolean =
    lineSequence().any { line ->
        val normalized = line.trim().uppercase()
        normalized.startsWith("#EXT-X-PART:") || normalized.startsWith("#EXT-X-PRELOAD-HINT:")
    }

internal fun YAdaptiveVariant.fits(
    maximumWidth: Int?,
    maximumHeight: Int?,
): Boolean =
    (maximumWidth == null || width == null || width <= maximumWidth) &&
        (maximumHeight == null || height == null || height <= maximumHeight)

internal fun String.guessContentType(): String =
    when (
        runCatching {
            URI(this)
                .path
                .orEmpty()
                .substringAfterLast('.')
                .lowercase()
        }.getOrDefault("")
    ) {
        "m3u8" -> HLS_CONTENT_TYPE
        "mpd" -> DASH_CONTENT_TYPE
        "ts" -> "video/mp2t"
        "mp4", "m4s", "m4v" -> "video/mp4"
        "aac" -> "audio/aac"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

internal fun String.safeSyntheticExtension(): String? =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
    }.getOrNull()
        ?.takeIf(SAFE_SYNTHETIC_EXTENSIONS::contains)

internal fun String.safeTemplateExtension(): String? =
    substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf(SAFE_SYNTHETIC_EXTENSIONS::contains)

internal fun YCacheIdentity.forStableAdaptiveUri(upstreamUri: String): YCacheIdentity =
    forAdaptiveResourceKey(stableAdaptiveResourceKey(upstreamUri))

internal fun YCacheIdentity.forAdaptiveResourceKey(resourceKey: String): YCacheIdentity =
    copy(
        version =
            buildString {
                append(version)
                append(':')
                append(resourceKey.sha256())
            },
    )

internal fun stableAdaptiveResourceKey(uri: String): String =
    runCatching {
        val parsed = URI(uri)
        buildString {
            parsed.scheme?.let { append(it.lowercase()).append("://") }
            parsed.host?.let { host ->
                append(host.lowercase())
                if (parsed.port >= 0) append(':').append(parsed.port)
            }
            append(parsed.path.orEmpty())
            val stableQuery =
                parsed.rawQuery
                    ?.split('&')
                    ?.filter(String::isNotBlank)
                    ?.filterNot { parameter ->
                        parameter.substringBefore('=').isEphemeralMediaCredentialParameter()
                    }?.sorted()
                    .orEmpty()
            if (stableQuery.isNotEmpty()) append('?').append(stableQuery.joinToString("&"))
        }.takeIf(String::isNotBlank)
    }.getOrNull() ?: uri.substringBefore('?').substringBefore('#')

private fun String.isEphemeralMediaCredentialParameter(): Boolean {
    val normalized = lowercase().replace("-", "").replace("_", "")
    return normalized in EPHEMERAL_MEDIA_QUERY_NAMES ||
        normalized.contains("signature") ||
        normalized.endsWith("token")
}

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal const val HLS_SEGMENT_RECOVERY_SECONDS = 16L
internal const val LOOPBACK_HOST = "127.0.0.1"
internal const val LOOPBACK_BACKLOG = 8
internal const val YCORE_HTTP_ROUTE_PREFIX = "ycore-resource"
internal const val NETWORK_BUFFER_BYTES = 64 * 1024

internal class ProgressiveManifestResponse : Exception()

internal fun hasProgressiveMediaSignature(
    bytes: ByteArray,
    size: Int,
): Boolean {
    fun byte(index: Int) = bytes[index].toInt() and 0xff
    if (size < 8) return false
    if (byte(0) == 0x1a && byte(1) == 0x45 && byte(2) == 0xdf && byte(3) == 0xa3) return true
    if (byte(0) == 0x47 && listOf(188, 204).any { size > it && byte(it) == 0x47 }) return true
    if (size > 196 && byte(4) == 0x47 && byte(196) == 0x47) return true
    // ISO base media and QuickTime. Accept only valid initial atom sizes.
    val atomSize =
        (byte(0).toLong() shl 24) or (byte(1).toLong() shl 16) or
            (byte(2).toLong() shl 8) or byte(3).toLong()
    return (atomSize == 0L || atomSize == 1L || atomSize >= 8L) &&
        bytes.decodeToString(4, 8) in setOf("ftyp", "moov", "mdat", "wide", "free", "styp", "moof")
}

internal const val MAX_HLS_MANIFEST_BYTES = 4 * 1024 * 1024
internal const val MAX_DASH_MANIFEST_BYTES = 8 * 1024 * 1024

/**
 * One route per HLS segment: 50,000 let a long live session hold tens of megabytes of routes. Eviction
 * takes the least recently referenced first, so this only has to cover one VOD title's playlists.
 */
internal const val MAX_ROUTES = 50_000
private const val MAX_HLS_RELOAD_QUERY_PARAMETERS = 8
internal const val INITIAL_BANDWIDTH_BITS_PER_SECOND = 25_000_000L
internal const val STARTUP_BUFFER_US = 10_000_000L
internal const val MAX_ABR_BUFFER_US = 30_000_000L
internal const val ABR_FEEDBACK_STALE_NS = 5_000_000_000L
internal const val MAX_HLS_ABR_VARIANTS = 8
internal const val MAX_DASH_ABR_REPRESENTATIONS = 8
private const val NANOS_PER_MICROSECOND = 1_000L
internal const val MICROS_PER_SECOND_LONG = 1_000_000L
internal const val DEFAULT_DASH_SEGMENT_DURATION_SECONDS = 2L
internal const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"
internal const val DASH_CONTENT_TYPE = "application/dash+xml"
internal const val WIDEVINE_SYSTEM_ID = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
internal val ALLOWED_METHODS = setOf("GET", "HEAD")
internal val PROXY_BUSY_RESPONSE =
    "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        .toByteArray(StandardCharsets.ISO_8859_1)
internal const val BUSY_REQUEST_DRAIN_BYTES = 4 * 1024
private val HTTP_BYTE_RANGE = Regex("^bytes=(\\d+)-(\\d*)$", RegexOption.IGNORE_CASE)
private val HLS_RELOAD_QUERY_NAMES =
    mapOf(
        "_hls_msn" to "_HLS_msn",
        "_hls_part" to "_HLS_part",
        "_hls_skip" to "_HLS_skip",
    )
internal val DASH_PERIOD_TAG = Regex("<\\s*(?:[A-Za-z0-9_.-]+:)?Period(?:\\s|>)", RegexOption.IGNORE_CASE)
internal val DASH_NUMBER_TOKEN = Regex("\\\$Number(?:%0\\d+d)?\\\$")
internal val DASH_TIME_TOKEN = Regex("\\\$Time(?:%0\\d+d)?\\\$")
private val SAFE_SYNTHETIC_EXTENSIONS =
    setOf("aac", "bin", "key", "m3u8", "m4s", "m4v", "mkv", "mov", "mp4", "mpd", "m2ts", "mts", "ts", "vtt", "webm")
private val EPHEMERAL_MEDIA_QUERY_NAMES =
    setOf(
        "authorization",
        "auth",
        "token",
        "accesstoken",
        "apikey",
        "expires",
        "expiry",
        "sig",
        "hmac",
        "hdnts",
        "hdnea",
        "policy",
        "keypairid",
        "xamzalgorithm",
        "xamzcredential",
        "xamzdate",
        "xamzexpires",
        "xamzsecuritytoken",
        "xamzsignature",
        "xamzsignedheaders",
    )

/**
 * The upstream answered a byte range with its whole body (200), as progressive transcodes and
 * one-shot signed links do; such a source can only be streamed from its start.
 */
internal fun Throwable.upstreamIgnoresByteRanges(): Boolean =
    generateSequence(this) { it.cause }
        .take(MAX_UPSTREAM_CAUSE_DEPTH)
        .any { cause ->
            (cause is YRangeReadException && cause.statusCode == 200) ||
                (cause is AndroidRangeResponseException && cause.statusCode == 200)
        }

private const val MAX_UPSTREAM_CAUSE_DEPTH = 8

internal fun IOException.canRetryHlsSegment(): Boolean {
    if (this is YRangeReadException) return failureKind in TRANSIENT_HLS_FAILURES
    if (this is AndroidRangeResponseException) return failureKind in TRANSIENT_HLS_FAILURES
    val status = mediaHttpStatus()
    if (status != null) return status in RETRYABLE_HLS_STATUS
    return generateSequence<Throwable>(this) { it.cause }.take(MAX_UPSTREAM_CAUSE_DEPTH).any {
        it is java.net.SocketException ||
            it is java.net.SocketTimeoutException ||
            it is java.net.UnknownHostException ||
            it is java.io.EOFException
    }
}

private val TRANSIENT_HLS_FAILURES =
    setOf(YTransportFailureKind.TransientIo, YTransportFailureKind.PrematureEof, YTransportFailureKind.ServerBusy)
private val RETRYABLE_HLS_STATUS = setOf(408, 429, 500, 502, 503, 504)
