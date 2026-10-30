package com.yfuse.core.sync.playback

/** A delayed event retains the generation in which its playback session began. */
internal class PlaybackSessionEpochs {
    private data class Key(
        val profileId: String,
        val mediaKey: String,
        val sessionId: String,
    )

    private val epochs = linkedMapOf<Key, Long>()

    fun isStale(
        profileId: String,
        mediaKey: String,
        sessionId: String?,
        epoch: Long,
    ): Boolean = sessionId?.let { epochs[Key(profileId, mediaKey, it)]?.let { captured -> captured != epoch } } == true

    fun remember(state: PlaybackStateRecord) {
        val sessionId = state.sessionId ?: return
        val key = Key(state.profileId, state.mediaKey, sessionId)
        epochs.remove(key)
        epochs[key] = state.progressEpoch
        while (epochs.size > 256) epochs.remove(epochs.keys.first())
    }

    fun clear() = epochs.clear()
}
