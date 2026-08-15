package com.yfuse.core.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

internal actual fun createPlaybackMediaProbeService(): PlaybackMediaProbeService {
    val context = androidAppContext
    return if (context == null) {
        PlaybackMediaProbeService { request ->
            PlaybackProbeResult(
                status = PlaybackProbeStatus.Unsupported,
                probe = request.baseline,
                detail = "应用上下文尚未就绪",
            )
        }
    } else {
        AndroidPlaybackMediaProbeService(context.applicationContext)
    }
}

/** Android MediaExtractor adapter; native/FFmpeg probes can implement the same common contract. */
private class AndroidPlaybackMediaProbeService(
    private val context: Context,
) : PlaybackMediaProbeService {
    // An ordinary map plus explicit eviction avoids a Kotlin 2.1 KMP actualization compiler bug
    // triggered by anonymous Java collection subclasses.
    private val cache =
        LinkedHashMap<String, PlaybackProbeResult>(MAX_PROBE_CACHE_ENTRIES, 0.75f, true)

    override suspend fun probe(request: PlaybackProbeRequest): PlaybackProbeResult {
        if (request.uri.isBlank() || request.baseline.usingServerTranscode) {
            return PlaybackProbeResult.metadataOnly(request.baseline, "转码流沿用服务端元数据")
        }
        if (request.baseline.discSource && !request.baseline.localSource) {
            return PlaybackProbeResult.metadataOnly(request.baseline, "远程光盘源由服务器解析")
        }
        val cacheKey = request.uri.sha256()
        synchronized(cache) { cache[cacheKey] }?.let { return it }

        val result =
            withTimeoutOrNull(request.timeoutMs.coerceIn(MIN_PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS)) {
                withContext(Dispatchers.IO) { inspect(request) }
            } ?: PlaybackProbeResult(
                status = PlaybackProbeStatus.TimedOut,
                probe = request.baseline,
                elapsedMs = request.timeoutMs,
                detail = "本机深度探测超时，继续使用服务端元数据",
            )
        synchronized(cache) {
            cache[cacheKey] = result
            while (cache.size > MAX_PROBE_CACHE_ENTRIES) {
                cache.remove(cache.keys.first())
            }
        }
        return result
    }

    private fun inspect(request: PlaybackProbeRequest): PlaybackProbeResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val extractor = MediaExtractor()
        return try {
            val headers =
                request.customUserAgent
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { mapOf("User-Agent" to it) }
                    .orEmpty()
            extractor.setDataSource(context, Uri.parse(request.uri), headers)
            val trackFormats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = trackFormats.firstOrNull { it.mimeType()?.startsWith("video/") == true }
            val audio = trackFormats.firstOrNull { it.mimeType()?.startsWith("audio/") == true }
            val source = request.baseline.source.enrichedWith(video)
            val durationMs =
                trackFormats
                    .mapNotNull { it.longOrNull(MediaFormat.KEY_DURATION) }
                    .maxOrNull()
                    ?.div(MICROSECONDS_PER_MILLISECOND)
            val styledSubtitles =
                request.baseline.styledSubtitles ||
                    trackFormats.any { it.mimeType() in STYLED_SUBTITLE_MIME_TYPES }
            val probe =
                request.baseline.copy(
                    source = source,
                    styledSubtitles = styledSubtitles,
                    drmProtected = request.baseline.drmProtected || !extractor.psshInfo.isNullOrEmpty(),
                    audioCodec = request.baseline.audioCodec ?: audio?.mimeType().toPlaybackAudioCodec(),
                    audioChannelCount =
                        request.baseline.audioChannelCount
                            ?: audio?.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                    durationMs = request.baseline.durationMs ?: durationMs,
                    probeDepth = PlaybackProbeDepth.PlatformExtractor,
                )
            PlaybackProbeResult(
                status = PlaybackProbeStatus.Complete,
                probe = probe,
                elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
                trackCount = trackFormats.size,
                detail = PlaybackProbeDepth.PlatformExtractor.label,
            )
        } catch (_: SecurityException) {
            PlaybackProbeResult(
                status = PlaybackProbeStatus.Unsupported,
                probe = request.baseline,
                elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
                detail = "媒体访问被系统拒绝，继续使用服务端元数据",
            )
        } catch (_: Exception) {
            // MediaExtractor exceptions frequently include the authenticated URI. Do not attach
            // the throwable to logs or diagnostics.
            PlaybackProbeResult(
                status = PlaybackProbeStatus.Failed,
                probe = request.baseline,
                elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
                detail = "本机无法读取媒体轨道，继续使用服务端元数据",
            )
        } finally {
            runCatching(extractor::release)
        }
    }
}

private fun PlaybackSourceRequirements.enrichedWith(format: MediaFormat?): PlaybackSourceRequirements {
    if (format == null) return this
    val mime = format.mimeType()
    val detectedCodec = mime.toPlaybackVideoCodec()
    val detectedRange = format.detectedDynamicRange(mime)
    val detectedDolby = detectedCodec == PlaybackVideoCodec.DolbyVision
    return copy(
        dolbyVision = dolbyVision || detectedDolby,
        needsDolbyDecoder = needsDolbyDecoder || detectedDolby,
        dynamicRange = dynamicRange ?: detectedRange,
        videoCodec = videoCodec ?: detectedCodec,
        width = width ?: format.intOrNull(MediaFormat.KEY_WIDTH),
        height = height ?: format.intOrNull(MediaFormat.KEY_HEIGHT),
        frameRate = frameRate ?: format.numberOrNull(MediaFormat.KEY_FRAME_RATE)?.toDouble(),
        bitrateBitsPerSecond = bitrateBitsPerSecond ?: format.intOrNull(MediaFormat.KEY_BIT_RATE),
        bitDepth = bitDepth ?: format.intOrNull(KEY_BIT_DEPTH),
        videoLevel = videoLevel ?: format.intOrNull(MediaFormat.KEY_LEVEL)?.toDouble(),
    )
}

private fun MediaFormat.detectedDynamicRange(mime: String?): String? =
    when {
        mime.toPlaybackVideoCodec() == PlaybackVideoCodec.DolbyVision -> "Dolby Vision"
        intOrNull(MediaFormat.KEY_COLOR_TRANSFER) == COLOR_TRANSFER_ST2084 -> "HDR10"
        intOrNull(MediaFormat.KEY_COLOR_TRANSFER) == COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }

private fun MediaFormat.mimeType(): String? = stringOrNull(MediaFormat.KEY_MIME)?.lowercase()

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaFormat.numberOrNull(key: String): Number? =
    if (!containsKey(key)) {
        null
    } else {
        runCatching { getInteger(key) }.getOrNull()
            ?: runCatching { getFloat(key) }.getOrNull()
    }

private fun MediaFormat.stringOrNull(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

private fun String?.toPlaybackVideoCodec(): PlaybackVideoCodec? =
    when (this?.lowercase()) {
        "video/avc" -> PlaybackVideoCodec.H264
        "video/hevc" -> PlaybackVideoCodec.Hevc
        "video/dolby-vision" -> PlaybackVideoCodec.DolbyVision
        "video/x-vnd.on2.vp8" -> PlaybackVideoCodec.Vp8
        "video/x-vnd.on2.vp9" -> PlaybackVideoCodec.Vp9
        "video/av01" -> PlaybackVideoCodec.Av1
        "video/mpeg2" -> PlaybackVideoCodec.Mpeg2
        "video/mp4v-es" -> PlaybackVideoCodec.Mpeg4
        "video/wvc1" -> PlaybackVideoCodec.Vc1
        else -> null
    }

private fun String?.toPlaybackAudioCodec(): PlaybackAudioCodec? =
    when (this?.lowercase()) {
        "audio/mp4a-latm" -> PlaybackAudioCodec.Aac
        "audio/mpeg" -> PlaybackAudioCodec.Mp3
        "audio/ac3" -> PlaybackAudioCodec.Ac3
        "audio/eac3" -> PlaybackAudioCodec.Eac3
        "audio/eac3-joc" -> PlaybackAudioCodec.Eac3Joc
        "audio/true-hd" -> PlaybackAudioCodec.TrueHd
        "audio/vnd.dts" -> PlaybackAudioCodec.Dts
        "audio/vnd.dts.hd" -> PlaybackAudioCodec.DtsHd
        "audio/ac4" -> PlaybackAudioCodec.Ac4
        "audio/flac" -> PlaybackAudioCodec.Flac
        "audio/opus" -> PlaybackAudioCodec.Opus
        "audio/vorbis" -> PlaybackAudioCodec.Vorbis
        "audio/raw" -> PlaybackAudioCodec.Pcm
        else -> null
    }

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

private const val KEY_BIT_DEPTH = "bit-depth"
private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
private const val MICROSECONDS_PER_MILLISECOND = 1_000L
private const val MIN_PROBE_TIMEOUT_MS = 500L
private const val MAX_PROBE_TIMEOUT_MS = 10_000L
private const val MAX_PROBE_CACHE_ENTRIES = 24
private val STYLED_SUBTITLE_MIME_TYPES =
    setOf(
        "text/x-ssa",
        "application/pgs",
        "application/vobsub",
        "application/dvbsubs",
    )
