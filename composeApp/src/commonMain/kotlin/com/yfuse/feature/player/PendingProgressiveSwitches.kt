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
     * The encoder stop for [index] has returned. True when the MP4 switch goes ahead, which it does
     * only while [stillCurrent]; the entry then stops being pending. An entry the viewer has left
     * keeps its mark.
     */
    fun settle(
        index: Int,
        stillCurrent: Boolean,
    ): Boolean {
        if (!stillCurrent) return false
        indices -= index
        return true
    }

    /** No switch is under way any more. */
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
