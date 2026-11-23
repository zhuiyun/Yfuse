package com.yfuse.feature.player

/** Late requests from a retired movie must not mark the next movie as inaccessible. */
internal class PlaybackProxyFailureState {
    data class Evidence(
        val httpStatus: Int? = null,
        val refusedRead: Boolean = false,
    )

    private var generation = 0L
    private var evidence = Evidence()

    @Synchronized
    fun beginSource(): Long {
        generation++
        evidence = Evidence()
        return generation
    }

    @Synchronized
    fun recordHttpStatus(
        sourceGeneration: Long,
        httpStatus: Int,
    ) {
        if (sourceGeneration != generation) return
        if (httpStatus in setOf(401, 403, 404, 410)) {
            evidence = Evidence(httpStatus = httpStatus)
        } else if (httpStatus in 200..299) {
            clearRefusedRead(sourceGeneration)
        }
    }

    @Synchronized
    fun recordRefusedRead(sourceGeneration: Long) {
        if (sourceGeneration == generation && evidence.httpStatus == null) evidence = Evidence(refusedRead = true)
    }

    @Synchronized
    fun clearRefusedRead(sourceGeneration: Long) {
        if (sourceGeneration == generation && evidence.refusedRead) evidence = Evidence()
    }

    @Synchronized
    fun snapshot(): Evidence = evidence
}
