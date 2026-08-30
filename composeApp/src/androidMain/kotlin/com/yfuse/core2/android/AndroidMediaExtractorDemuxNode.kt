package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.yfuse.core2.graph.YDemuxNode
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.sync.YMediaTimestampTimeline
import java.nio.ByteBuffer
import java.util.UUID

internal data class YAndroidMediaSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
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
) : YDemuxNode {
    override val name: String = "MediaExtractor"

    private val appContext = context.applicationContext
    private val timeline = YMediaTimestampTimeline()
    private var extractor: MediaExtractor? = null
    private var mediaDataSource: MediaDataSource? = null
    private var currentSource: YAndroidMediaSource? = null
    private var selectedTracks = emptySet<Int>()

    fun open(source: YAndroidMediaSource) {
        release()
        val opened = createExtractor()
        try {
            opened.setPrivateDataSource(source)
            extractor = opened
            currentSource = source
            selectedTracks = emptySet()
        } catch (throwable: Throwable) {
            runCatching { opened.release() }
            runCatching { mediaDataSource?.close() }
            mediaDataSource = null
            throw throwable
        }
    }

    val trackCount: Int get() = extractor?.trackCount ?: 0

    fun trackFormat(index: Int): MediaFormat = requireExtractor().getTrackFormat(index)

    /** Reads only a bounded container prefix for YCore-owned metadata parsing. */
    fun readSourcePrefix(maximumBytes: Int): ByteArray? {
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

    fun drmInitializationData(schemeUuid: UUID): ByteArray? =
        requireExtractor()
            .psshInfo
            ?.get(schemeUuid)
            ?.copyOf()

    fun findFirstTrack(mimePrefix: String): Int? =
        (0 until trackCount).firstOrNull { index ->
            trackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith(mimePrefix, ignoreCase = true) == true
        }

    fun selectTrack(index: Int) {
        require(index in 0 until trackCount) { "Track index $index is outside 0 until $trackCount" }
        if (index in selectedTracks) return
        requireExtractor().selectTrack(index)
        selectedTracks = selectedTracks + index
    }

    fun unselectTrack(index: Int) {
        if (index !in selectedTracks) return
        requireExtractor().unselectTrack(index)
        selectedTracks = selectedTracks - index
    }

    /**
     * Reads one compressed sample into a direct buffer. The buffer is reused by callers and is
     * copied only once more into MediaCodec's input buffer; decoded output remains zero-copy on the
     * configured Surface path.
     */
    fun readSample(target: ByteBuffer): YExtractorSample? {
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

    fun advance(): Boolean = requireExtractor().advance()

    fun seekTo(
        positionUs: Long,
        mode: Int = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
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

    private fun MediaExtractor.setPrivateDataSource(source: YAndroidMediaSource) {
        val parsed = Uri.parse(source.uri)
        when (parsed.scheme?.lowercase()) {
            "content", "android.resource", "file" -> setDataSource(appContext, parsed, source.headers)
            "http", "https" -> {
                if (source.uri.isAdaptiveManifestUri()) {
                    setDataSource(source.uri, source.headers)
                    return
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
                        cacheDirectory = appContext.cacheDir,
                        cacheIdentity = source.cacheIdentity,
                        cacheMaximumBytes = source.cacheMaximumBytes,
                        createTransport = {
                            AndroidAdaptiveHttpMediaTransport(
                                createCronet = { AndroidCronetMediaTransport(appContext) },
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
                        cacheDirectory = appContext.cacheDir,
                        cacheIdentity = source.cacheIdentity,
                        cacheMaximumBytes = source.cacheMaximumBytes,
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
                        cacheDirectory = appContext.cacheDir,
                        cacheIdentity = source.cacheIdentity,
                        cacheMaximumBytes = source.cacheMaximumBytes,
                        createTransport = ::AndroidSmbMediaTransport,
                    )
                mediaDataSource = rangeSource
                setDataSource(rangeSource)
            }
            else -> setDataSource(source.uri, source.headers)
        }
    }
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

private fun String.isAdaptiveManifestUri(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

private fun MediaCodec.CryptoInfo.toExtractorCryptoInfo(): YExtractorCryptoInfo =
    YExtractorCryptoInfo(
        numberOfSubSamples = numSubSamples,
        clearBytes = numBytesOfClearData?.copyOf(),
        encryptedBytes = numBytesOfEncryptedData?.copyOf(),
        key = requireNotNull(key).copyOf(),
        initializationVector = requireNotNull(iv).copyOf(),
        mode = mode,
    )

private const val MEDIA_EXTRACTOR_SAMPLE_TIME_UNAVAILABLE = -1L
