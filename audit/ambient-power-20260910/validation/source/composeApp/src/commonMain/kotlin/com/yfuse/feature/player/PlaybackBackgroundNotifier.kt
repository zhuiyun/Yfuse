package com.yfuse.feature.player

/**
 * Process-local signal for persisting the freshest sampled player position when the app UI hides.
 *
 * Reporters register only for their lifetime and remove themselves on close. The notifier owns no
 * playback state; it merely asks each active reporter to enqueue its most recent state on the same
 * serialized reporting actor that already orders seek/pause/stop events.
 */
internal object PlaybackBackgroundNotifier {
    private val lock = Any()
    private val listeners = LinkedHashSet<() -> Unit>()

    fun register(listener: () -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        var removed = false
        return {
            synchronized(lock) {
                if (!removed) {
                    removed = true
                    listeners -= listener
                }
            }
        }
    }

    fun notifyAppBackground() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener -> runCatching(listener) }
    }

    internal fun listenerCount(): Int = synchronized(lock) { listeners.size }
}

internal fun notifyPlaybackAppBackground() {
    PlaybackBackgroundNotifier.notifyAppBackground()
}
