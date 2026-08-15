package com.yfuse.core.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Headless libmpv probe backed by the bundled FFmpeg demuxers.
 *
 * It is used only when MediaExtractor fails or the source needs native demuxing. No frame is
 * rendered and the authenticated URI is never copied into a result, exception or log entry.
 */
internal class MpvPlaybackMediaProbe(
    private val context: Context,
) {
    suspend fun probe(request: PlaybackProbeRequest): PlaybackProbeResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val loaded = CompletableDeferred<Boolean>()
        val observer =
            object : MPVLib.EventObserver {
                override fun eventProperty(property: String) = Unit

                override fun eventProperty(
                    property: String,
                    value: Long,
                ) = Unit

                override fun eventProperty(
                    property: String,
                    value: Double,
                ) = Unit

                override fun eventProperty(
                    property: String,
                    value: Boolean,
                ) = Unit

                override fun eventProperty(
                    property: String,
                    value: String,
                ) = Unit

                override fun event(eventId: Int) {
                    when (eventId) {
                        MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> loaded.complete(true)
                        MPVLib.MpvEvent.MPV_EVENT_END_FILE,
                        MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN,
                        -> loaded.complete(false)
                    }
                }
            }
        var instance: MPVLib? = null
        return try {
            val mpv =
                MPVLib.create(context)
                    ?: return failedResult(request, startedAtMs, "FFmpeg 探测器不可用")
            instance = mpv
            if (
                mpv.setOptionString("config", "no") < 0 ||
                mpv.setOptionString("idle", "yes") < 0 ||
                mpv.setOptionString("vo", "null") < 0 ||
                mpv.setOptionString("ao", "null") < 0
            ) {
                return failedResult(request, startedAtMs, "FFmpeg 探测器初始化失败")
            }
            mpv.setOptionString("pause", "yes")
            mpv.setOptionString("load-scripts", "no")
            request.customUserAgent.trim().takeIf(String::isNotEmpty)?.let { userAgent ->
                mpv.setOptionString("user-agent", userAgent)
            }
            mpv.init()
            mpv.addObserver(observer)
            mpv.command(arrayOf("loadfile", mpv.prepareDiscUrl(request), "replace"))
            if (!loaded.await()) {
                return failedResult(request, startedAtMs, "FFmpeg 无法读取媒体轨道")
            }
            mpv.readProbe(request, startedAtMs)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            failedResult(request, startedAtMs, "FFmpeg 无法读取媒体轨道")
        } finally {
            instance?.let { mpv ->
                runCatching { mpv.removeObserver(observer) }
                runCatching { mpv.command(arrayOf("stop")) }
                runCatching { mpv.destroy() }
            }
        }
    }

    private fun MPVLib.readProbe(
        request: PlaybackProbeRequest,
        startedAtMs: Long,
    ): PlaybackProbeResult {
        val trackCount = propertyInt("track-list/count")?.coerceAtLeast(0) ?: 0
        if (trackCount == 0) {
            return failedResult(request, startedAtMs, "FFmpeg 未发现可播放轨道")
        }
        val baseline = request.baseline
        val tracks = (0 until trackCount).mapNotNull { index -> readTrack(index) }
        val video = tracks.firstOrNull { it.type == "video" }
        val audio = tracks.firstOrNull { it.type == "audio" }
        val videoCodec = video?.codec.toPlaybackVideoCodec()
        val dynamicRange = detectedDynamicRange(videoCodec)
        val detectedDiscKind =
            when (propertyString("file-format")?.lowercase()) {
                "libdvdnav", "dvd" -> PlaybackDiscKind.Dvd
                "libbluray", "bluray" -> PlaybackDiscKind.BluRay
                else -> baseline.discKind
            }
        val source =
            baseline.source.copy(
                dolbyVision = baseline.source.dolbyVision || videoCodec == PlaybackVideoCodec.DolbyVision,
                needsDolbyDecoder =
                    baseline.source.needsDolbyDecoder || videoCodec == PlaybackVideoCodec.DolbyVision,
                dynamicRange = baseline.source.dynamicRange ?: dynamicRange,
                videoCodec = baseline.source.videoCodec ?: videoCodec,
                width = baseline.source.width ?: video?.width,
                height = baseline.source.height ?: video?.height,
                frameRate = baseline.source.frameRate ?: video?.frameRate,
                bitrateBitsPerSecond = baseline.source.bitrateBitsPerSecond ?: video?.bitrate,
            )
        val durationMs =
            baseline.durationMs
                ?: propertyDouble("duration")
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.times(1_000.0)
                    ?.toLong()
        return PlaybackProbeResult(
            status = PlaybackProbeStatus.Complete,
            probe =
                baseline.copy(
                    source = source,
                    styledSubtitles =
                        baseline.styledSubtitles ||
                            tracks.any { it.type == "sub" && it.codec in STYLED_NATIVE_SUBTITLES },
                    audioCodec = baseline.audioCodec ?: audio?.codec.toPlaybackAudioCodec(),
                    audioChannelCount = baseline.audioChannelCount ?: audio?.channels,
                    durationMs = durationMs,
                    discKind = detectedDiscKind,
                    probeDepth = PlaybackProbeDepth.NativeFfmpeg,
                ),
            elapsedMs = elapsedSince(startedAtMs),
            trackCount = trackCount,
            detail = PlaybackProbeDepth.NativeFfmpeg.label,
        )
    }

    private fun MPVLib.readTrack(index: Int): NativeTrack? {
        val type = propertyString("track-list/$index/type") ?: return null
        return NativeTrack(
            type = type,
            codec = propertyString("track-list/$index/codec")?.lowercase(),
            width = propertyInt("track-list/$index/demux-w")?.takeIf { it > 0 },
            height = propertyInt("track-list/$index/demux-h")?.takeIf { it > 0 },
            frameRate = propertyDouble("track-list/$index/demux-fps")?.takeIf { it > 0.0 },
            bitrate = propertyInt("track-list/$index/demux-bitrate")?.takeIf { it > 0 },
            channels = propertyInt("track-list/$index/demux-channel-count")?.takeIf { it > 0 },
        )
    }

    private fun MPVLib.detectedDynamicRange(codec: PlaybackVideoCodec?): String? {
        if (codec == PlaybackVideoCodec.DolbyVision) return "Dolby Vision"
        return when (propertyString("video-params/gamma")?.lowercase()) {
            "pq" -> "HDR10"
            "hlg" -> "HLG"
            else -> null
        }
    }

    private fun MPVLib.prepareDiscUrl(request: PlaybackProbeRequest): String {
        if (!request.baseline.localSource || !request.uri.startsWith("file://", ignoreCase = true)) {
            return request.uri
        }
        val path = Uri.parse(request.uri).path?.takeIf(String::isNotBlank) ?: return request.uri
        return when (request.baseline.discKind) {
            PlaybackDiscKind.Dvd -> {
                setPropertyString("dvd-device", path)
                "dvd://"
            }
            PlaybackDiscKind.BluRay,
            PlaybackDiscKind.Bdmv,
            -> {
                setPropertyString(
                    "bluray-device",
                    bluRayDiscRoot(path),
                )
                "bd://"
            }
            else -> request.uri
        }
    }

    private fun MPVLib.propertyString(name: String): String? =
        runCatching { getPropertyString(name) }.getOrNull()?.takeIf(String::isNotBlank)

    private fun MPVLib.propertyInt(name: String): Int? =
        runCatching { getPropertyInt(name) }.getOrNull()

    private fun MPVLib.propertyDouble(name: String): Double? =
        runCatching { getPropertyDouble(name) }.getOrNull()

    private fun failedResult(
        request: PlaybackProbeRequest,
        startedAtMs: Long,
        detail: String,
    ) = PlaybackProbeResult(
        status = PlaybackProbeStatus.Failed,
        probe = request.baseline,
        elapsedMs = elapsedSince(startedAtMs),
        detail = detail,
    )
}

private data class NativeTrack(
    val type: String,
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val bitrate: Int?,
    val channels: Int?,
)

private fun elapsedSince(startedAtMs: Long): Long =
    (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)

private val STYLED_NATIVE_SUBTITLES =
    setOf("ass", "ssa", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle")
