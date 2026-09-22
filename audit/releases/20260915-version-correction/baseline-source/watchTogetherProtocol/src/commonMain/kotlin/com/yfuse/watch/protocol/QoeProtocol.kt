package com.yfuse.watch.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class QoeEngine { Exo, Mpv, Mdk }

@Serializable
enum class QoePlatformApiBucket { Api26To28, Api29To30, Api31To32, Api33To34, Api35Plus }

@Serializable
enum class QoeDeviceFamily {
    Qualcomm,
    MediaTek,
    GoogleTensor,
    SamsungExynos,
    Unisoc,
    Rockchip,
    Amlogic,
    Other,
    Unknown,
}

@Serializable
enum class QoeCodecFamily {
    Avc,
    Hevc,
    Av1,
    Vp9,
    Mpeg2,
    Aac,
    Ac3,
    Eac3,
    TrueHd,
    Dts,
    Flac,
    Opus,
    Pcm,
    Other,
    None,
}

@Serializable
enum class QoeContainerFamily { Mp4, Mkv, Webm, MpegTs, Hls, Dash, Other }

@Serializable
enum class QoeResolutionBucket { AudioOnly, Sd, Hd, FullHd, Qhd, UltraHd, AboveUltraHd, Unknown }

@Serializable
enum class QoeDynamicRange { Sdr, Hdr10, Hlg, DolbyVision, Other, Unknown }

@Serializable
enum class QoePlaybackMethod { Direct, Transcoded }

/**
 * Deliberately contains no media, server, account, URL, IP, locale or stable device identifier.
 * Numeric values are fixed upper-bound buckets so an individual session cannot be reconstructed.
 */
@Serializable
data class AnonymousPlaybackQoeReport(
    val schemaVersion: Int = QoeProtocol.SCHEMA_VERSION,
    val appVersion: String,
    val engine: QoeEngine,
    val platformApi: QoePlatformApiBucket,
    /** Coarse SoC vendor only; the raw model, board and hardware strings never leave the device. */
    val deviceFamily: QoeDeviceFamily,
    val videoCodec: QoeCodecFamily,
    val audioCodec: QoeCodecFamily,
    val container: QoeContainerFamily,
    val resolution: QoeResolutionBucket,
    val dynamicRange: QoeDynamicRange,
    val playbackMethod: QoePlaybackMethod,
    val startupUpperBoundMs: Int,
    val observedUpperBoundSeconds: Int,
    val rebufferEventsUpperBound: Int,
    val droppedFramesPerMinuteUpperBound: Int,
    val avSyncAbsoluteUpperBoundMs: Int? = null,
    val networkRecoveryAttemptsUpperBound: Int = 0,
    val networkRecoverySuccessesUpperBound: Int = 0,
)

object QoeProtocol {
    const val SCHEMA_VERSION = 1
    val STARTUP_BUCKETS_MS = setOf(1_000, 2_500, 5_000, 12_000, 30_000, 120_000, Int.MAX_VALUE)
    val OBSERVATION_BUCKETS_SECONDS = setOf(30, 60, 300, 900, 3_600, Int.MAX_VALUE)
    val REBUFFER_BUCKETS = setOf(0, 1, 2, 4, 8, 16, Int.MAX_VALUE)
    val DROPPED_FRAME_RATE_BUCKETS = setOf(0, 1, 5, 15, 30, 60, 300, Int.MAX_VALUE)
    val AV_SYNC_BUCKETS_MS = setOf(20, 40, 80, 160, 320, 1_000, 5_000, Int.MAX_VALUE)
    val RECOVERY_BUCKETS = setOf(0, 1, 2, 4, 8, Int.MAX_VALUE)
    private val APP_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
    private val VIDEO_CODECS =
        setOf(
            QoeCodecFamily.Avc,
            QoeCodecFamily.Hevc,
            QoeCodecFamily.Av1,
            QoeCodecFamily.Vp9,
            QoeCodecFamily.Mpeg2,
            QoeCodecFamily.Other,
            QoeCodecFamily.None,
        )
    private val AUDIO_CODECS =
        setOf(
            QoeCodecFamily.Aac,
            QoeCodecFamily.Ac3,
            QoeCodecFamily.Eac3,
            QoeCodecFamily.TrueHd,
            QoeCodecFamily.Dts,
            QoeCodecFamily.Flac,
            QoeCodecFamily.Opus,
            QoeCodecFamily.Pcm,
            QoeCodecFamily.Other,
            QoeCodecFamily.None,
        )

    fun isValid(report: AnonymousPlaybackQoeReport): Boolean =
        report.schemaVersion == SCHEMA_VERSION &&
            APP_VERSION.matches(report.appVersion) &&
            report.videoCodec in VIDEO_CODECS &&
            report.audioCodec in AUDIO_CODECS &&
            report.startupUpperBoundMs in STARTUP_BUCKETS_MS &&
            report.observedUpperBoundSeconds in OBSERVATION_BUCKETS_SECONDS &&
            report.rebufferEventsUpperBound in REBUFFER_BUCKETS &&
            report.droppedFramesPerMinuteUpperBound in DROPPED_FRAME_RATE_BUCKETS &&
            (report.avSyncAbsoluteUpperBoundMs == null || report.avSyncAbsoluteUpperBoundMs in AV_SYNC_BUCKETS_MS) &&
            report.networkRecoveryAttemptsUpperBound in RECOVERY_BUCKETS &&
            report.networkRecoverySuccessesUpperBound in RECOVERY_BUCKETS &&
            report.networkRecoverySuccessesUpperBound <= report.networkRecoveryAttemptsUpperBound
}
