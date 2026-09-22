package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackHdrFormat
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackVideoCodec
import com.yfuse.core.playback.YCoreRuntimeAssessment
import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeCodecFamily
import com.yfuse.watch.protocol.QoeContainerFamily
import com.yfuse.watch.protocol.QoeDeviceFamily
import com.yfuse.watch.protocol.QoeDynamicRange
import com.yfuse.watch.protocol.QoeEngine
import com.yfuse.watch.protocol.QoePlatformApiBucket
import com.yfuse.watch.protocol.QoePlaybackMethod
import com.yfuse.watch.protocol.QoeProtocol
import com.yfuse.watch.protocol.QoeResolutionBucket
import kotlin.math.absoluteValue
import kotlin.math.ceil

internal fun anonymousPlaybackQoeReport(
    appVersion: String,
    platformApiLevel: Int,
    socManufacturer: String?,
    hardware: String?,
    engine: PlayerEngine,
    probe: PlaybackMediaProbe,
    state: PlaybackState,
    assessment: YCoreRuntimeAssessment,
    networkRecoveryAttempts: Int,
    networkRecoverySuccesses: Int,
): AnonymousPlaybackQoeReport =
    AnonymousPlaybackQoeReport(
        appVersion = appVersion.normalizedQoeVersion(),
        engine =
            when (engine) {
                PlayerEngine.Exo -> QoeEngine.Exo
                PlayerEngine.Mpv -> QoeEngine.Mpv
                PlayerEngine.Mdk -> QoeEngine.Mdk
            },
        platformApi = platformApiLevel.qoeApiBucket(),
        deviceFamily = qoeDeviceFamily(socManufacturer, hardware),
        videoCodec = probe.source.videoCodec.qoeFamily(),
        audioCodec = probe.audioCodec.qoeFamily(),
        container = probe.container.qoeContainer(state.transcoding),
        resolution = probe.qoeResolution(),
        dynamicRange = probe.source.hdrFormat.qoeDynamicRange(),
        playbackMethod = if (state.transcoding) QoePlaybackMethod.Transcoded else QoePlaybackMethod.Direct,
        startupUpperBoundMs =
            assessment.health.startupTimeMs
                .orZero()
                .upperBound(QoeProtocol.STARTUP_BUCKETS_MS),
        observedUpperBoundSeconds =
            ceil(assessment.health.observedPlaybackMs.coerceAtLeast(0L) / 1_000.0)
                .toLong()
                .upperBound(QoeProtocol.OBSERVATION_BUCKETS_SECONDS),
        rebufferEventsUpperBound =
            assessment.health.rebufferEvents
                .toLong()
                .upperBound(QoeProtocol.REBUFFER_BUCKETS),
        droppedFramesPerMinuteUpperBound =
            ceil(assessment.health.droppedFramesPerMinute.toDouble())
                .toLong()
                .upperBound(QoeProtocol.DROPPED_FRAME_RATE_BUCKETS),
        avSyncAbsoluteUpperBoundMs =
            state.diagnostics.avSyncOffsetMs
                ?.absoluteValue
                ?.upperBound(QoeProtocol.AV_SYNC_BUCKETS_MS),
        networkRecoveryAttemptsUpperBound =
            networkRecoveryAttempts.toLong().upperBound(QoeProtocol.RECOVERY_BUCKETS),
        networkRecoverySuccessesUpperBound =
            networkRecoverySuccesses
                .coerceAtMost(networkRecoveryAttempts)
                .toLong()
                .upperBound(QoeProtocol.RECOVERY_BUCKETS),
    )

private fun Long?.orZero(): Long = this ?: 0L

private fun Long.upperBound(buckets: Set<Int>): Int = buckets.sorted().firstOrNull { this <= it } ?: buckets.max()

private fun String.normalizedQoeVersion(): String {
    val normalized =
        trim()
            .take(32)
            .map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trimStart { !it.isLetterOrDigit() }
    return normalized.ifEmpty { "unknown" }
}

private fun Int.qoeApiBucket(): QoePlatformApiBucket =
    when {
        this <= 28 -> QoePlatformApiBucket.Api26To28
        this <= 30 -> QoePlatformApiBucket.Api29To30
        this <= 32 -> QoePlatformApiBucket.Api31To32
        this <= 34 -> QoePlatformApiBucket.Api33To34
        else -> QoePlatformApiBucket.Api35Plus
    }

private fun qoeDeviceFamily(
    socManufacturer: String?,
    hardware: String?,
): QoeDeviceFamily {
    val value = listOfNotNull(socManufacturer, hardware).joinToString(" ").trim().lowercase()
    if (value.isEmpty()) return QoeDeviceFamily.Unknown
    return when {
        "qualcomm" in value || "qcom" in value || "snapdragon" in value || "msm" in value ->
            QoeDeviceFamily.Qualcomm
        "mediatek" in value || "mtk" in value || "dimensity" in value -> QoeDeviceFamily.MediaTek
        "google" in value || "tensor" in value || "gs101" in value || "gs201" in value || "zuma" in value ->
            QoeDeviceFamily.GoogleTensor
        "samsung" in value || "exynos" in value -> QoeDeviceFamily.SamsungExynos
        "unisoc" in value || "spreadtrum" in value -> QoeDeviceFamily.Unisoc
        "rockchip" in value || Regex("(?:^|\\s)rk\\d+").containsMatchIn(value) -> QoeDeviceFamily.Rockchip
        "amlogic" in value -> QoeDeviceFamily.Amlogic
        else -> QoeDeviceFamily.Other
    }
}

private fun PlaybackVideoCodec?.qoeFamily(): QoeCodecFamily =
    when (this) {
        PlaybackVideoCodec.H264 -> QoeCodecFamily.Avc
        PlaybackVideoCodec.Hevc,
        PlaybackVideoCodec.DolbyVision,
        -> QoeCodecFamily.Hevc
        PlaybackVideoCodec.Av1 -> QoeCodecFamily.Av1
        PlaybackVideoCodec.Vp9 -> QoeCodecFamily.Vp9
        PlaybackVideoCodec.Mpeg2 -> QoeCodecFamily.Mpeg2
        null -> QoeCodecFamily.None
        else -> QoeCodecFamily.Other
    }

private fun PlaybackAudioCodec?.qoeFamily(): QoeCodecFamily =
    when (this) {
        PlaybackAudioCodec.Aac -> QoeCodecFamily.Aac
        PlaybackAudioCodec.Ac3 -> QoeCodecFamily.Ac3
        PlaybackAudioCodec.Eac3,
        PlaybackAudioCodec.Eac3Joc,
        -> QoeCodecFamily.Eac3
        PlaybackAudioCodec.TrueHd -> QoeCodecFamily.TrueHd
        PlaybackAudioCodec.Dts,
        PlaybackAudioCodec.DtsHd,
        -> QoeCodecFamily.Dts
        PlaybackAudioCodec.Flac -> QoeCodecFamily.Flac
        PlaybackAudioCodec.Opus -> QoeCodecFamily.Opus
        PlaybackAudioCodec.Pcm -> QoeCodecFamily.Pcm
        null -> QoeCodecFamily.None
        else -> QoeCodecFamily.Other
    }

private fun String?.qoeContainer(transcoding: Boolean): QoeContainerFamily {
    if (transcoding) return QoeContainerFamily.Hls
    val value = this?.lowercase().orEmpty()
    return when {
        "m3u8" in value || "hls" in value -> QoeContainerFamily.Hls
        "dash" in value || "mpd" in value -> QoeContainerFamily.Dash
        "matroska" in value || "mkv" in value -> QoeContainerFamily.Mkv
        "webm" in value -> QoeContainerFamily.Webm
        "mpegts" in value || value == "ts" || value == "m2ts" -> QoeContainerFamily.MpegTs
        "mp4" in value || "mov" in value -> QoeContainerFamily.Mp4
        else -> QoeContainerFamily.Other
    }
}

private fun PlaybackMediaProbe.qoeResolution(): QoeResolutionBucket {
    if (source.videoCodec == null && source.width == null && source.height == null) {
        return if (audioCodec != null) QoeResolutionBucket.AudioOnly else QoeResolutionBucket.Unknown
    }
    val height = source.height ?: source.width?.let { it * 9 / 16 }
    return when {
        height == null -> QoeResolutionBucket.Unknown
        height <= 576 -> QoeResolutionBucket.Sd
        height <= 720 -> QoeResolutionBucket.Hd
        height <= 1_080 -> QoeResolutionBucket.FullHd
        height <= 1_440 -> QoeResolutionBucket.Qhd
        height <= 2_160 -> QoeResolutionBucket.UltraHd
        else -> QoeResolutionBucket.AboveUltraHd
    }
}

private fun PlaybackHdrFormat?.qoeDynamicRange(): QoeDynamicRange =
    when (this) {
        null -> QoeDynamicRange.Sdr
        PlaybackHdrFormat.Hdr10,
        PlaybackHdrFormat.Hdr10Plus,
        -> QoeDynamicRange.Hdr10
        PlaybackHdrFormat.Hlg -> QoeDynamicRange.Hlg
        PlaybackHdrFormat.DolbyVision -> QoeDynamicRange.DolbyVision
    }
