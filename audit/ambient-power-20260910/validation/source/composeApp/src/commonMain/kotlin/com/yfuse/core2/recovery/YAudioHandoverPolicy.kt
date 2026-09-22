package com.yfuse.core2.recovery

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

private const val SPEED_EPSILON = 0.0001f
