package com.yfuse.core2.recovery

import com.yfuse.core2.capability.YAudioOutputPath
import kotlin.math.abs

/** True when encoded audio must hand over to the decodable PCM path. */
fun requiresPcmAudioPath(
    protectedContent: Boolean,
    passthroughRejected: Boolean,
    speed: Float,
): Boolean {
    require(speed.isFinite() && speed > 0f)
    return protectedContent || passthroughRejected || abs(speed - 1f) > SPEED_EPSILON
}

/**
 * True when PCM that stood in for passthrough may hand back: the device would take this track as
 * passthrough, the sink never refused it, and nothing only PCM can do (a speed other than 1.0, an
 * audio delay) is asked of it any more. Without this, one trip to 1.25x or one delay nudge kept a
 * Dolby or DTS track on PCM for the rest of the title.
 */
fun passthroughRestorable(
    currentPath: YAudioOutputPath,
    devicePath: YAudioOutputPath,
    protectedContent: Boolean,
    passthroughRejected: Boolean,
    speed: Float,
    audioDelayMs: Long,
): Boolean =
    currentPath == YAudioOutputPath.DecodePcm &&
        devicePath == YAudioOutputPath.Passthrough &&
        audioDelayMs == 0L &&
        !requiresPcmAudioPath(protectedContent, passthroughRejected, speed)

private const val SPEED_EPSILON = 0.0001f
