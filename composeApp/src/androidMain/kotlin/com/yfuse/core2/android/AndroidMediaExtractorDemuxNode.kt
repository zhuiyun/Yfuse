package com.yfuse.core2.android

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.yfuse.core2.graph.YDemuxNode
import com.yfuse.core2.network.YSourceProtocol
import java.nio.ByteBuffer

internal data class YAndroidMediaSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
)

internal data class YExtractorSample(
    val trackIndex: Int,
    val data: ByteBuffer,
    val presentationTimeUs: Long,
    val flags: Int,
)

/**
 * Platform demux node for Core2 NativeDirect.
 *
 * This class only separates compressed samples from their container. It never decodes video and it
 * never logs the source URI or headers, because both may contain Emby/Jellyfin credentials.
 */
internal class AndroidMediaExtractorDemuxNode(
    context: Context,
    private val createExtractor: () -> MediaExtractor = ::MediaExtractor,
) : YDemuxNode {
    override val name: String = "MediaExtractor"

    private val appContext = context.applicationContext
    private var extractor: MediaExtractor? = null
    private var mediaDataSource: MediaDataSource? = null
    private var selectedTracks = emptySet<Int>()

    fun open(source: YAndroidMediaSource) {
        release()
        val opened = createExtractor()
        try {
            opened.setPrivateDataSource(source)
            extractor = opened
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
        return YExtractorSample(
            trackIndex = trackIndex,
            data = target.slice(),
            presentationTimeUs = opened.sampleTime,
            flags = opened.sampleFlags,
        )
    }

    fun advance(): Boolean = requireExtractor().advance()

    fun seekTo(
        positionUs: Long,
        mode: Int = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
    ) {
        requireExtractor().seekTo(positionUs.coerceAtLeast(0L), mode)
    }

    override fun flush() = Unit

    override fun release() {
        extractor?.let { runCatching { it.release() } }
        mediaDataSource?.let { runCatching { it.close() } }
        extractor = null
        mediaDataSource = null
        selectedTracks = emptySet()
    }

    private fun requireExtractor(): MediaExtractor =
        checkNotNull(extractor) { "MediaExtractor demux node has not been opened" }

    private fun MediaExtractor.setPrivateDataSource(source: YAndroidMediaSource) {
        val parsed = Uri.parse(source.uri)
        when (parsed.scheme?.lowercase()) {
            "content", "android.resource", "file" -> setDataSource(appContext, parsed, source.headers)
            "http", "https" -> {
                val rangeSource = AndroidHttpRangeMediaDataSource(source.uri, source.headers)
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
