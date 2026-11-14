package com.yfuse.feature.player

/** Late requests from a retired movie must not mark the next movie as inaccessible. */
internal class PlaybackProxyFailureState {
    private var generation = 0L
    private var status: Int? = null

    @Synchronized
    fun beginSource(): Long {
        generation++
        status = null
        return generation
    }

    @Synchronized
    fun recordHttpStatus(
        sourceGeneration: Long,
        httpStatus: Int,
    ) {
        if (sourceGeneration == generation && httpStatus in setOf(401, 403, 404, 410)) {
            status = httpStatus
        }
    }

    @Synchronized
    fun terminalHttpStatus(): Int? = status
}
