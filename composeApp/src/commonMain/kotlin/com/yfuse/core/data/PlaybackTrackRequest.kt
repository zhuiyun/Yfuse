package com.yfuse.core.data

/**
 * A track choice made on the detail page, handed to the player that is about to open.
 *
 * Deliberately *not* part of the navigation config. The config is identity — which item,
 * which file, where to resume — and it survives process death and back-stack restoration.
 * "start this one on the 国语 track" is neither: it is a decision made a second ago about a
 * player that has not opened yet, and resurrecting it when someone returns to a restored
 * back stack days later would silently override whatever they last chose in the player.
 *
 * So it lives here, is keyed to the entry it was made for, and is consumed once. A player
 * that opens for anything else finds nothing.
 *
 * Languages rather than stream indices, because that is the only thing both sides agree on.
 * Emby numbers the streams in the file; each engine numbers the tracks its own way after
 * demuxing, and there is no dependable mapping between the two. A language is what the user
 * picked anyway — they chose 国语, not stream 3.
 */
class PlaybackTrackRequest {
    private var pending: Pending? = null

    private data class Pending(
        val itemId: String,
        val audioLanguage: String?,
        /** [SUBTITLES_OFF] to start with subtitles disabled. */
        val subtitleLanguage: String?,
    )

    data class Tracks(
        val audioLanguage: String?,
        val subtitleLanguage: String?,
    )

    fun set(
        itemId: String,
        audioLanguage: String? = null,
        subtitleLanguage: String? = null,
    ) {
        if (itemId.isBlank() || (audioLanguage == null && subtitleLanguage == null)) {
            pending = null
            return
        }
        pending = Pending(itemId, audioLanguage, subtitleLanguage)
    }

    /**
     * Takes the request for this entry, if there is one, and clears it.
     *
     * Clearing on read is what keeps a choice from leaking into the next episode: the queue
     * moves on by itself, and a preference stated for 第4集 was not stated for 第5集.
     */
    fun consume(itemId: String?): Tracks? {
        val current = pending ?: return null
        if (itemId == null || current.itemId != itemId) return null
        pending = null
        return Tracks(current.audioLanguage, current.subtitleLanguage)
    }

    companion object {
        const val SUBTITLES_OFF = "__off__"
    }
}
