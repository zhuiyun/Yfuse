package com.yfuse.feature.player

import com.yfuse.core.logging.AppLog
import dev.jdtech.mpv.MPVLib

internal fun logMpvFileLoaded(
    player: MPVLib?,
    item: PlayerMediaItem?,
    state: PlaybackState,
    resumePositionMs: Long?,
    surfaceGeneration: Long,
    surfaceValid: Boolean,
    platformProxy: Boolean,
) {
    val source = item?.activeVersion
    val detectedVideoCodec =
        runCatching { player?.getPropertyString("video-codec") }.getOrNull()
    val detectedDecoder =
        runCatching { player?.getPropertyString("hwdec-current") }.getOrNull()
    val detectedDynamicRange =
        runCatching { player?.getPropertyString("video-params/gamma") }.getOrNull()
    AppLog.info(
        category = "player.mpv",
        event = "file_loaded",
        message = "mpv loaded the selected media",
        attributes =
            mapOf(
                "itemIndex" to state.currentIndex.toString(),
                "resumePositionMs" to (resumePositionMs ?: 0L).coerceAtLeast(0L).toString(),
                "videoCodec" to (detectedVideoCodec ?: source?.sourceVideoCodec.orEmpty()),
                "decoder" to (detectedDecoder ?: "software"),
                "dynamicRange" to
                    (detectedDynamicRange ?: source?.sourceDynamicRange.orEmpty()),
                "dolbyProfile" to (source?.dolbyProfile?.toString() ?: "unknown"),
                "sourceAtmos" to (source?.dolbyAtmos == true).toString(),
                "surfaceGeneration" to surfaceGeneration.toString(),
                "surfaceValid" to surfaceValid.toString(),
                "platformProxy" to
                    platformProxy.toString(),
            ),
    )
}
