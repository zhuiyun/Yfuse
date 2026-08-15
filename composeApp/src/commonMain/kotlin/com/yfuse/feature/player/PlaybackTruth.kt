package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackSourceRequirements
import com.yfuse.core.playback.PlaybackVideoCodec
import com.yfuse.core.playback.detectPlaybackDiscKind

/** True when this request must begin on the server output rather than the original file. */
internal fun PlayerMediaItem.startsWithServerTranscode(quality: PlaybackQuality): Boolean =
    transcodeUrl.isNotBlank() &&
        (quality.requiresServerTranscode || playMethod == PlaybackMethod.Transcode)

/** The method shown before an engine has enough runtime facts to refine it. */
internal fun PlayerMediaItem.effectivePlaybackMethod(quality: PlaybackQuality): PlaybackMethod =
    if (startsWithServerTranscode(quality)) PlaybackMethod.Transcode else playMethod

/** Human-readable cause paired with the actual method, never inferred from a badge. */
internal fun PlayerMediaItem.initialFallbackReason(quality: PlaybackQuality): String? =
    when {
        forcedTranscodeReason != null && transcodeUrl.isNotBlank() -> forcedTranscodeReason
        quality.requiresServerTranscode && transcodeUrl.isNotBlank() -> "用户选择 ${quality.label}"
        quality.requiresServerTranscode -> "服务器未提供转码地址，已保留原始播放方式"
        playMethod == PlaybackMethod.DirectStream -> "服务器协商为直串流"
        playMethod == PlaybackMethod.Transcode -> "服务器协商要求转码"
        else -> null
    }

/** Selects the server output before a backend can render an unsupported source frame. */
internal fun PlayerMediaItem.withForcedServerTranscode(reason: String): PlayerMediaItem {
    val preparedUrl = transcodeUrl.ifBlank { fallbackTranscodeUrl }
    if (preparedUrl.isBlank()) return this
    return copy(
        transcodeUrl = preparedUrl,
        playMethod = PlaybackMethod.Transcode,
        forcedTranscodeReason = reason,
    )
}

internal fun PlayerMediaVersion.sourceRequirements(): PlaybackSourceRequirements =
    PlaybackSourceRequirements(
        dolbyVision = dolbyVision,
        needsDolbyDecoder = needsDolbyDecoder,
        dynamicRange = sourceDynamicRange,
        videoCodec = sourceVideoCodec.toPlaybackVideoCodec(),
        width = sourceWidth,
        height = sourceHeight,
        frameRate = sourceFrameRate,
        bitrateBitsPerSecond = sourceBitrateBps,
        bitDepth = sourceBitDepth,
        videoLevel = sourceVideoLevel,
    )

/** Fast PlaybackInfo-backed probe; FFmpeg probing can enrich the same core model later. */
internal fun PlayerMediaItem?.playbackMediaProbe(usingServerTranscode: Boolean = false): PlaybackMediaProbe {
    val version = this?.activeVersion
    val sourceUrl =
        if (usingServerTranscode) {
            this?.transcodeUrl
        } else {
            this?.url
        }.orEmpty()
    return PlaybackMediaProbe(
        container = version?.container,
        discSource = version?.discSource == true,
        source =
            version?.sourceRequirements()
                ?: PlaybackSourceRequirements(
                    dolbyVision = false,
                    needsDolbyDecoder = false,
                    dynamicRange = null,
                ),
        hasServerTranscode =
            this?.let { item ->
                item.transcodeUrl.isNotBlank() || item.fallbackTranscodeUrl.isNotBlank()
            } == true,
        usingServerTranscode = usingServerTranscode,
        discKind =
            detectPlaybackDiscKind(
                container = version?.container,
                labelHint = version?.label,
                declaredDiscSource = version?.discSource == true,
            ),
        localSource =
            sourceUrl.startsWith("file://", ignoreCase = true) ||
                sourceUrl.startsWith("content://", ignoreCase = true),
    )
}

private fun String?.toPlaybackVideoCodec(): PlaybackVideoCodec? {
    val normalized = this?.trim()?.lowercase().orEmpty()
    return PlaybackVideoCodec.entries.firstOrNull { codec ->
        normalized in codec.embyNames || codec.embyNames.any(normalized::startsWith)
    }
}

internal fun PlaybackDiagnostics.hasActiveDolbyVisionOutput(): Boolean =
    videoOutput.contains("Dolby Vision", ignoreCase = true) &&
        videoOutput.contains("首帧已输出") &&
        !videoOutput.contains("未声明支持")

internal fun PlaybackDiagnostics.hasActiveDolbyAtmosOutput(): Boolean =
    audioOutput.contains("源码输出") &&
        (
            audioOutput.contains("Atmos", ignoreCase = true) ||
                audioOutput.contains("TrueHD", ignoreCase = true)
        )

internal fun PlayerMediaItem.sourceDynamicRange(transcoding: Boolean): String =
    activeVersion
        ?.sourceDynamicRange
        .orEmpty()
        .takeUnless { transcoding }
        .orEmpty()

internal fun PlayerMediaItem.sourceAudioFormat(transcoding: Boolean): String =
    activeVersion
        ?.sourceAudio
        .orEmpty()
        .takeUnless { transcoding }
        .orEmpty()

internal fun PlayerMediaItem.sourceVideoHeight(transcoding: Boolean): Int =
    activeVersion?.sourceHeight?.takeUnless { transcoding } ?: 0

internal fun initialPlaybackDiagnostics(
    engine: String,
    decoder: String,
    item: PlayerMediaItem?,
    quality: PlaybackQuality,
    transcoding: Boolean = item?.startsWithServerTranscode(quality) == true,
): PlaybackDiagnostics =
    PlaybackDiagnostics(
        engine = engine,
        decoder = decoder,
        playMethod =
            item?.effectivePlaybackMethod(quality)?.label
                ?: PlaybackMethod.DirectPlay.label,
        requestedQuality = quality.label,
        videoCodec = item?.activeVersion?.sourceVideoCodec?.uppercase() ?: "未知",
        videoWidth = item?.activeVersion?.sourceWidth?.takeUnless { transcoding } ?: 0,
        dynamicRange = item?.sourceDynamicRange(transcoding).orEmpty(),
        audioFormat = item?.sourceAudioFormat(transcoding).orEmpty(),
        fallbackReason = item?.initialFallbackReason(quality),
        bitrateBitsPerSecond =
            item
                ?.activeVersion
                ?.sourceBitrateBps
                ?.takeUnless { transcoding }
                ?.toLong()
                ?: 0L,
    )
