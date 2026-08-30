package com.yfuse.feature.player

/**
 * Latest-wins seek reducer shared by touch, remote-control and Cast paths.
 *
 * UI may emit repeated positions while a finger/remote button is held. Replacing [positionMs]
 * keeps only the newest request; [sequence] lets a coroutine restart its short debounce window even
 * when two requests happen to target the same millisecond.
 */
internal data class SeekMergeState(
    val positionMs: Long? = null,
    val sequence: Long = 0L,
) {
    fun offer(positionMs: Long): SeekMergeState =
        SeekMergeState(
            positionMs = positionMs.coerceAtLeast(0L),
            sequence = sequence + 1L,
        )

    fun consumed(expectedSequence: Long): SeekMergeState =
        if (sequence == expectedSequence) copy(positionMs = null) else this
}

internal const val SEEK_MERGE_DEBOUNCE_MS = 120L
