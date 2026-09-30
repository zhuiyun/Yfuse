package com.yfuse.feature.player

/**
 * The queue entries whose step onto the progressive MP4 is under way: the HLS encoder is being
 * stopped before the MP4 request, which carries the same PlaySessionId, may start. While an entry
 * is here the stream ladder answers "switching" for it ([PlaybackStreamRung.ProgressivePending]).
 * Exo, mpv and MDK each keep one, next to their other per-entry sets.
 */
internal class PendingProgressiveSwitches {
    private val indices = mutableSetOf<Int>()

    operator fun contains(index: Int): Boolean = index in indices

    /** The step onto the MP4 for [index] has begun: its HLS encoder is being stopped. */
    fun start(index: Int) {
        indices += index
    }

    /**
     * The encoder stop for [index] has returned: the entry stops being pending whatever happens
     * next. True when the MP4 switch goes ahead, which it does only while [stillCurrent]. The MP4
     * of an entry the viewer has left never loads, and a mark kept for it would answer every later
     * failure of that entry with "switching".
     */
    fun settle(
        index: Int,
        stillCurrent: Boolean,
    ): Boolean {
        indices -= index
        return stillCurrent
    }

    /** Nothing counts as switching any more: the engine switched items or retried. */
    fun clear() {
        indices.clear()
    }

    /** Keeps each mark with its entry when the queue around it changes from [previous] to [updated]. */
    fun remap(
        previous: List<PlayerMediaItem>,
        updated: List<PlayerMediaItem>,
    ) {
        val remapped = remapPlaybackQueueIndices(indices, previous, updated)
        indices.clear()
        indices.addAll(remapped)
    }
}
