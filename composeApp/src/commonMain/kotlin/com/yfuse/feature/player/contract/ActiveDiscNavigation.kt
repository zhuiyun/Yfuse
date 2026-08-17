package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand

/**
 * Process-local binding for the disc-navigation popup.
 *
 * The player already owns the backend lifecycle; this bridge only lets common UI issue a direct
 * title/chapter command without threading an engine instance through every composable. The owner
 * identity check prevents an outgoing engine from clearing a newer handover binding.
 */
object ActiveDiscNavigation {
    private data class Binding(
        val owner: Any,
        val backend: DiscNavigationBackend,
    )

    private var binding: Binding? = null

    val isBound: Boolean get() = binding != null

    internal fun bind(
        owner: Any,
        backend: DiscNavigationBackend,
    ) {
        binding = Binding(owner = owner, backend = backend)
    }

    internal fun unbind(owner: Any) {
        if (binding?.owner === owner) binding = null
    }

    fun selectTitle(index: Int): Boolean = binding?.backend?.selectTitle(index) == true

    fun selectChapter(index: Int): Boolean = binding?.backend?.selectChapter(index) == true

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean =
        binding?.backend?.sendMenuCommand(command) == true
}
