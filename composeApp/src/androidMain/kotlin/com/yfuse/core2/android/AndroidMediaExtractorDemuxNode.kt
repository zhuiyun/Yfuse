package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.yfuse.core2.graph.YDemuxNode
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.sync.YMediaTimestampTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

internal data class YAndroidMediaSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val credentials: YTransportCredentials? = null,
    val bitrateBitsPerSecond: Long = 0L,
    val cacheIdentity: YCacheIdentity? = null,
    val cacheMaximumBytes: Long = 0L,
)

internal data class YExtractorSample(
    val trackIndex: Int,
    val data: ByteBuffer,
    val presentationTimeUs: Long,
    val flags: Int,
    val cryptoInfo: YExtractorCryptoInfo? = null,
)

/**
 * The extractor surface [AndroidMediaExtractorReadAheadNode] owns.
 *
 * Named separately from the concrete node so the read-ahead queue policy - watermarks, seek
 * invalidation, per-track backpressure, end-of-input ordering - is testable without a real
 * MediaExtractor, which a JVM unit test cannot drive.
 */
internal interface YPlatformExtractorSource : YDemuxNode {
    fun open(source: YAndroidMediaSource)

    val trackCount: Int

    fun trackFormat(index: Int): MediaFormat

    fun findFirstTrack(mimePrefix: String): Int?

    fun readSourcePrefix(maximumBytes: Int): ByteArray?

    fun drmInitializationData(schemeUuid: UUID): ByteArray?

    fun setMediaBitRateBitsPerSecond(value: Long)

    fun transportQoeSnapshot(): YTransportPrefetchQoeSnapshot?

    fun blockedForegroundReadMs(): Long

    fun selectTrack(index: Int)

    fun unselectTrack(index: Int)

    fun seekTo(positionUs: Long)

    fun readSample(target: ByteBuffer): YExtractorSample?

    fun advance(): Boolean
}

/**
 * Platform demux node for Core2 NativeDirect.
 *
 * This class only separates compressed samples from their container. It never decodes video and it
 * never logs the source URI or headers, because both may contain Emby/Jellyfin credentials.
 *
 * MediaExtractor exposes container PTS values. Core2 rebases the first selected sample to 0 so its
 * public position/seek contract matches Exo, mpv and MDK instead of leaking a file-specific start
 * timestamp into YMediaClock.
 */
internal class AndroidMediaExtractorDemuxNode(
    context: Context,
    private val createExtractor: () -> MediaExtractor = ::MediaExtractor,
    private val onBlockingReadStateChanged: ((Boolean) -> Unit)? = null,
) : YPlatformExtractorSource {
    override val name: String = "MediaExtractor"

    private val appContext = context.applicationContext
    private val timeline = YMediaTimestampTimeline()
    private var extractor: MediaExtractor? = null
    private var mediaDataSource: MediaDataSource? = null
    private var currentSource: YAndroidMediaSource? = null
    private var selectedTracks = emptySet<Int>()

    override fun open(source: YAndroidMediaSource) {
        release()
        val opened = createExtractor()
        try {
            opened.setPrivateDataSource(source)
            extractor = opened
            currentSource = source
            selectedTracks = emptySet()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            runCatching { opened.release() }
            runCatching { mediaDataSource?.close() }
            mediaDataSource = null
            throw throwable
        }
    }

    override val trackCount: Int get() = extractor?.trackCount ?: 0

    override fun trackFormat(index: Int): MediaFormat = requireExtractor().getTrackFormat(index)

    /** Reads only a bounded container prefix for YCore-owned metadata parsing. */
    override fun readSourcePrefix(maximumBytes: Int): ByteArray? {
        require(maximumBytes > 0)
        mediaDataSource?.let { source ->
            val knownSize = runCatching { source.size }.getOrDefault(-1L)
            val targetSize =
                knownSize
                    .takeIf { it >= 0L }
                    ?.coerceAtMost(maximumBytes.toLong())
                    ?.toInt()
                    ?: maximumBytes
            val result = ByteArray(targetSize)
            var total = 0
            while (total < result.size) {
                val read = source.readAt(total.toLong(), result, total, result.size - total)
                if (read <= 0) break
                total += read
            }
            return result.copyOf(total)
        }

        val openedSource = currentSource ?: return null
        val scheme = Uri.parse(openedSource.uri).scheme?.lowercase()
        if (scheme !in setOf("content", "android.resource", "file")) return null
        return appContext.contentResolver.openInputStream(Uri.parse(openedSource.uri))?.use { input ->
            val result = ByteArray(maximumBytes)
            var total = 0
            while (total < result.size) {
                val read = input.read(result, total, result.size - total)
                if (read <= 0) break
                total += read
            }
            result.copyOf(total)
        }
    }

    override fun drmInitializationData(schemeUuid: UUID): ByteArray? {
        requireExtractor()
            .psshInfo
            ?.get(schemeUuid)
            ?.copyOf()
            ?.let { return it }
        if (schemeUuid != WIDEVINE_UUID) return null
        val source = currentSource ?: return null
        val root =
            readBoundedUri(
                source.uri,
                source.headers,
                source.credentials,
                MAX_ADAPTIVE_DRM_MANIFEST_BYTES,
            )?.decodeToString()
                ?: return null
        return resolveWidevineAdaptiveInitializationData(root, source.uri) { childUri ->
            readBoundedUri(
                childUri,
                source.headers,
                source.credentials,
                MAX_ADAPTIVE_DRM_MANIFEST_BYTES,
            )?.decodeToString()
        }
    }

    override fun setMediaBitRateBitsPerSecond(value: Long) {
        (mediaDataSource as? AndroidTransportMediaDataSource)
            ?.setMediaBitRateBitsPerSecond(value)
    }

    override fun transportQoeSnapshot(): YTransportPrefetchQoeSnapshot? =
        (mediaDataSource as? AndroidTransportMediaDataSource)?.qoeSnapshot()

    /** Lock-free; safe to call from the codec/render pump. See the data source for why. */
    override fun blockedForegroundReadMs(): Long =
        (mediaDataSource as? AndroidTransportMediaDataSource)?.blockedForegroundReadMs() ?: 0L

    override fun findFirstTrack(mimePrefix: String): Int? =
        (0 until trackCount).firstOrNull { index ->
            trackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith(mimePrefix, ignoreCase = true) == true
        }

    override fun selectTrack(index: Int) {
        require(index in 0 until trackCount) { "Track index $index is outside 0 until $trackCount" }
        if (index in selectedTracks) return
        requireExtractor().selectTrack(index)
        selectedTracks = selectedTracks + index
    }

    override fun unselectTrack(index: Int) {
        if (index !in selectedTracks) return
        requireExtractor().unselectTrack(index)
        selectedTracks = selectedTracks - index
    }

    /**
     * Reads one compressed sample into a direct buffer. The buffer is reused by callers and is
     * copied only once more into MediaCodec's input buffer; decoded output remains zero-copy on the
     * configured Surface path.
     */
    override fun readSample(target: ByteBuffer): YExtractorSample? {
        val opened = requireExtractor()
        val trackIndex = opened.sampleTrackIndex
        if (trackIndex < 0) return null
        target.clear()
        val size = opened.readSampleData(target, 0)
        if (size < 0) return null
        target.position(0)
        target.limit(size)
        val rawPresentationTimeUs = opened.validSampleTimeUs()
        val flags = opened.sampleFlags
        val cryptoInfo =
            if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                MediaCodec
                    .CryptoInfo()
                    .also { info ->
                        require(opened.getSampleCryptoInfo(info)) { "Encrypted sample has no CryptoInfo" }
                    }.toExtractorCryptoInfo()
            } else {
                null
            }
        return YExtractorSample(
            trackIndex = trackIndex,
            data = target.slice(),
            presentationTimeUs = timeline.presentationTimeUs(rawPresentationTimeUs),
            flags = flags,
            cryptoInfo = cryptoInfo,
        )
    }

    override fun advance(): Boolean = requireExtractor().advance()

    override fun seekTo(positionUs: Long) = seekTo(positionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

    fun seekTo(
        positionUs: Long,
        mode: Int,
    ) {
        establishTimelineOrigin()
        requireExtractor().seekTo(
            timeline.sourceTimeUs(positionUs).coerceAtLeast(0L),
            mode,
        )
    }

    override fun flush() = Unit

    override fun release() {
        extractor?.let { runCatching { it.release() } }
        mediaDataSource?.let { runCatching { it.close() } }
        extractor = null
        mediaDataSource = null
        currentSource = null
        selectedTracks = emptySet()
        timeline.reset()
    }

    private fun establishTimelineOrigin() {
        if (timeline.established) return
        timeline.establish(requireExtractor().validSampleTimeUs())
    }

    private fun MediaExtractor.validSampleTimeUs(): Long =
        sampleTime
            .takeUnless {
                it == MEDIA_EXTRACTOR_SAMPLE_TIME_UNAVAILABLE
            } ?: 0L

    private fun requireExtractor(): MediaExtractor =
        checkNotNull(extractor) {
            "MediaExtractor demux node has not been opened"
        }

    private fun readBoundedUri(
        uri: String,
        headers: Map<String, String>,
        credentials: YTransportCredentials?,
        maximumBytes: Int,
    ): ByteArray? {
        require(maximumBytes > 0)
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase()) {
            "content", "android.resource", "file" ->
                appContext.contentResolver.openInputStream(parsed)?.use { input ->
                    input.readBounded(maximumBytes)
                }
            "http", "https" ->
                runBlocking {
                    val transport = AndroidHttpMediaTransport(followSafeRedirects = true)
                    try {
                        val response =
                            transport.open(
                                YMediaTransportRequest(
                                    uri = uri,
                                    protocol =
                                        if (parsed.scheme.equals("https", ignoreCase = true)) {
                                            YSourceProtocol.Https
                                        } else {
                                            YSourceProtocol.Http
                                        },
                                    headers = headers,
                                    credentials = credentials,
                                ),
                            )
                        require(response.statusCode in 200..299) {
                            "Adaptive DRM manifest request failed"
                        }
                        response.contentLength?.let { length ->
                            require(length <= maximumBytes.toLong()) {
                                "Adaptive DRM manifest is too large"
                            }
                        }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DRM_MANIFEST_IO_BUFFER_BYTES)
                        while (true) {
                            val count = transport.read(buffer, 0, buffer.size)
                            if (count < 0) break
                            if (count == 0) continue
                            require(output.size() <= maximumBytes - count) {
                                "Adaptive DRM manifest is too large"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } finally {
                        transport.close()
                    }
                }
            else -> null
        }
    }

    private fun MediaExtractor.setPrivateDataSource(source: YAndroidMediaSource) {
        val parsed = Uri.parse(source.uri)
        when (parsed.scheme?.lowercase()) {
            "content", "android.resource", "file" -> setDataSource(appContext, parsed, source.headers)
            "http", "https" -> {
                if (source.uri.isAdaptiveManifestUri()) {
                    setDataSource(source.uri, source.headers)
                    return
                }
                val cronetHostHealth = AndroidCronetHostHealth.shared
                // Shared with every other open of this URI in the process, so the probe's
                // resolved redirect and accepted-range facts carry over to the player and the
                // preload instead of being paid again on each open.
                val routeState =
                    AndroidSourceRouteStateRegistry.shared
                        .forSource(source.uri, onCronetDisabled = cronetHostHealth::recordFailure)
                        .apply {
                            // The Play-services Cronet dynamite module on the affected Android 9
                            // device resolves an API class that is absent from its own class loader.
                            // Keep the media path on the validated OkHttp range transport on API 28
                            // and below; this also avoids paying for a known-failing provider probe.
                            if (!shouldAttemptCronetMediaTransport(Build.VERSION.SDK_INT)) disableCronet()
                            // An origin that already refused Cronet in this process would otherwise
                            // charge every later playback the full open timeout before falling back.
                            if (!cronetHostHealth.isAvailable(source.uri)) disableCronet()
                        }
                val rangeSource =
                    AndroidTransportMediaDataSource(
                        uri = source.uri,
                        protocol =
                            if (parsed.scheme.equals("https", ignoreCase = true)) {
                                YSourceProtocol.Https
                            } else {
                                YSourceProtocol.Http
                            },
                        headers = source.headers,
                        credentials = source.credentials,
                        initialMediaBitRateBitsPerSecond = source.bitrateBitsPerSecond,
                        cacheDirectory = appContext.cacheDir,
                        cacheIdentity = source.cacheIdentity,
                        cacheMaximumBytes = source.cacheMaximumBytes,
                        onBlockingReadStateChanged = onBlockingReadStateChanged,
                        createTransport = {
                            AndroidAdaptiveHttpMediaTransport(
                                routeState = routeState,
                                createCronet = {
                                    AndroidCronetMediaTransport(
                                        context = appContext,
                                        followMediaRedirects = true,
                                        allowCrossProtocolRedirects = true,
                                        redirectState = routeState.redirectState,
                                    )
                                },
                                createOkHttp = {
                                    AndroidHttpMediaTransport(
                                        followSafeRedirects = true,
                                        allowCrossProtocolRedirects = true,
                                        redirectState = routeState.redirectState,
                                    )
                                },
                            )
                        },
                    )
                mediaDataSource = rangeSource
                setDataSource(rangeSource)
            }
            "webdav", "webdavs" -> {
                val tls = parsed.scheme.equals("webdavs", ignoreCase = true)
                val normalizedUri =
                    source.uri.replaceFirst(
                        Regex("(?i)^webdavs?://"),
                        if (tls) "https://" else "http://",
                    )
                val rangeSource =
                    AndroidTransportMediaDataSource(
                        uri = normalizedUri,
                        protocol = if (tls) YSourceProtocol.WebDavTls else YSourceProtocol.WebDav,
                        headers = source.headers,
                        credentials = source.credentials,
                        initialMediaBitRateBitsPerSecond = source.bitrateBitsPerSecond,
                        cacheDirectory = appContext.cacheDir,
                        cacheIdentity = source.cacheIdentity,
                        cacheMaximumBytes = source.cacheMaximumBytes,
                        onBlockingReadStateChanged = onBlockingReadStateChanged,
                        createTransport = ::AndroidHttpMediaTransport,
                    )
                mediaDataSource = rangeSource
                setDataSource(rangeSource)
            }
            "smb" -> {
                val rangeSource =
                    AndroidTransportMediaDataSource(
                        uri = source.uri,
                        protocol = YSourceProtocol.Smb,
                        headers = source.headers,
                        credentials = source.credentials,
                        initialMediaBitRateBitsPerSecond = source.bitrateBitsPerSecond,
                        cacheDirectory = appContext.cacheDir,
                        cacheIdentity = source.cacheIdentity,
                        cacheMaximumBytes = source.cacheMaximumBytes,
                        onBlockingReadStateChanged = onBlockingReadStateChanged,
                        createTransport = ::AndroidSmbMediaTransport,
                    )
                mediaDataSource = rangeSource
                setDataSource(rangeSource)
            }
            else -> setDataSource(source.uri, source.headers)
        }
    }
}

private fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DRM_MANIFEST_IO_BUFFER_BYTES)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(output.size() <= maximumBytes - count) { "Adaptive DRM manifest is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun MediaFormat.maxInputSizeOr(defaultBytes: Int): Int =
    if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1)
    } else {
        defaultBytes.coerceAtLeast(1)
    }

internal fun String.isCore2RemoteMediaUri(): Boolean =
    substringBefore(':', missingDelimiterValue = "").lowercase() in
        setOf("http", "https", "smb", "webdav", "webdavs")

internal fun shouldAttemptCronetMediaTransport(androidApi: Int): Boolean = androidApi >= Build.VERSION_CODES.Q

private fun String.isAdaptiveManifestUri(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

private fun MediaCodec.CryptoInfo.toExtractorCryptoInfo(): YExtractorCryptoInfo =
    cryptoPatternBlocks().let { pattern ->
        YExtractorCryptoInfo(
            numberOfSubSamples = numSubSamples,
            clearBytes = numBytesOfClearData?.copyOf(),
            encryptedBytes = numBytesOfEncryptedData?.copyOf(),
            key = requireNotNull(key).copyOf(),
            initializationVector = requireNotNull(iv).copyOf(),
            mode = mode,
            encryptedBlocks = pattern.first,
            clearBlocks = pattern.second,
        )
    }

private fun MediaCodec.CryptoInfo.cryptoPatternBlocks(): Pair<Int, Int> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Api31CryptoInfo.patternBlocks(this)
    } else {
        // CryptoInfo#getPattern is not public before API 31. CENC/CTR samples use an all-encrypted
        // pattern, represented by zeroes; avoiding the unavailable getter also keeps ordinary
        // protected playback from crashing on Android 8-11.
        0 to 0
    }

@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private object Api31CryptoInfo {
    fun patternBlocks(info: MediaCodec.CryptoInfo): Pair<Int, Int> =
        info.getPattern().let { pattern -> pattern.encryptBlocks to pattern.skipBlocks }
}

private const val MEDIA_EXTRACTOR_SAMPLE_TIME_UNAVAILABLE = -1L
private const val MAX_ADAPTIVE_DRM_MANIFEST_BYTES = 8 * 1024 * 1024
private const val DRM_MANIFEST_IO_BUFFER_BYTES = 32 * 1024
private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
