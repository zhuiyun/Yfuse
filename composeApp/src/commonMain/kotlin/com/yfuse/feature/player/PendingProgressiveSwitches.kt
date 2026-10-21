package com.yfuse.feature.player

/**
 * The queue entries whose step onto the progressive MP4 is under way: the HLS encoder is being
 * stopped before the MP4 request, which carries the same PlaySessionId, may start. While an entry
 * is here the stream ladder answers "switching" for it ([PlaybackStreamRung.ProgressivePending]).
 * Exo, mpv and MDK each keep one, next to their other per-entry sets.
 */
internal class PendingProgressiveSwitches {
    /** Identity, rather than index alone, distinguishes retries of the same queue entry. */
    class Attempt internal constructor(
        internal val index: Int,
    )

    private val attempts = mutableMapOf<Int, Attempt>()

    operator fun contains(index: Int): Boolean = index in attempts

    /** The step onto the MP4 for [index] has begun: its HLS encoder is being stopped. */
    fun start(index: Int): Attempt = Attempt(index).also { attempts[index] = it }

    /**
     * An old completion cannot clear a newer attempt, even after clear/start on the same index.
     * A moved entry loses its pending mark but cannot apply a callback holding its old index.
     */
    fun settle(
        attempt: Attempt,
        stillCurrent: Boolean,
    ): Boolean {
        val entry = attempts.entries.firstOrNull { it.value === attempt } ?: return false
        attempts.remove(entry.key)
        return stillCurrent && entry.key == attempt.index
    }

    /** Nothing counts as switching any more: the engine switched items or retried. */
    fun clear() {
        attempts.clear()
    }

    /** Keeps each mark with its entry when the queue around it changes from [previous] to [updated]. */
    fun remap(
        previous: List<PlayerMediaItem>,
        updated: List<PlayerMediaItem>,
    ) {
        val remapped =
            attempts.entries
                .mapNotNull { (index, attempt) ->
                    remapPlaybackQueueIndices(setOf(index), previous, updated)
                        .singleOrNull()
                        ?.let { it to attempt }
                }.toMap()
        attempts.clear()
        attempts.putAll(remapped)
    }
}
