package com.yfuse.core.model

enum class PlaybackSegmentType(
    val skipLabel: String,
) {
    Intro("跳过片头"),
    Credits("跳过片尾"),
}

/**
 * A server-provided segment in milliseconds. Credits usually have no explicit
 * end; the player then advances to the next queue item or seeks to the end.
 */
data class PlaybackSegment(
    val type: PlaybackSegmentType,
    val startMs: Long,
    val endMs: Long?,
) {
    fun contains(
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        val end = endMs ?: durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        return positionMs in startMs until end
    }
}
