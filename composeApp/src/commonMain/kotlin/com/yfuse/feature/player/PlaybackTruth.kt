package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality

/** True when this request must begin on the server output rather than the original file. */
internal fun PlayerMediaItem.startsWithServerTranscode(quality: PlaybackQuality): Boolean =
    transcodeUrl.isNotBlank() &&
        (quality.requiresServerTranscode || playMethod == PlaybackMethod.Transcode)

/** The method shown before an engine has enough runtime facts to refine it. */
internal fun PlayerMediaItem.effectivePlaybackMethod(quality: PlaybackQuality): PlaybackMethod =
    if (startsWithServerTranscode(quality)) PlaybackMethod.Transcode else playMethod

/** Human-readable cause paired with the actual method, never inferred from a badge. */
internal fun PlayerMediaItem.initialFallbackReason(quality: PlaybackQuality): String? = when {
    quality.requiresServerTranscode && transcodeUrl.isNotBlank() -> "用户选择 ${quality.label}"
    quality.requiresServerTranscode -> "服务器未提供转码地址，已保留原始播放方式"
    playMethod == PlaybackMethod.DirectStream -> "服务器协商为直串流"
    playMethod == PlaybackMethod.Transcode -> "服务器协商要求转码"
    else -> null
}

internal fun PlayerMediaItem.sourceDynamicRange(transcoding: Boolean): String =
    activeVersion?.sourceDynamicRange.orEmpty().takeUnless { transcoding }.orEmpty()

internal fun PlayerMediaItem.sourceAudioFormat(transcoding: Boolean): String =
    activeVersion?.sourceAudio.orEmpty().takeUnless { transcoding }.orEmpty()

internal fun PlayerMediaItem.sourceVideoHeight(transcoding: Boolean): Int =
    activeVersion?.sourceHeight?.takeUnless { transcoding } ?: 0

internal fun initialPlaybackDiagnostics(
    engine: String,
    decoder: String,
    item: PlayerMediaItem?,
    quality: PlaybackQuality,
    transcoding: Boolean = item?.startsWithServerTranscode(quality) == true,
): PlaybackDiagnostics = PlaybackDiagnostics(
    engine = engine,
    decoder = decoder,
    playMethod = item?.effectivePlaybackMethod(quality)?.label
        ?: PlaybackMethod.DirectPlay.label,
    requestedQuality = quality.label,
    videoWidth = item?.activeVersion?.sourceWidth?.takeUnless { transcoding } ?: 0,
    dynamicRange = item?.sourceDynamicRange(transcoding).orEmpty(),
    audioFormat = item?.sourceAudioFormat(transcoding).orEmpty(),
    fallbackReason = item?.initialFallbackReason(quality),
    bitrateBitsPerSecond = item?.activeVersion?.sourceBitrateBps
        ?.takeUnless { transcoding }
        ?.toLong()
        ?: 0L,
)
