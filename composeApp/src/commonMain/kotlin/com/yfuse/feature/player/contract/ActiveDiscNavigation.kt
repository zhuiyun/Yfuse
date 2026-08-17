package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState

/**
 * Process-local binding for the disc-navigation popup and platform remote input.
 *
 * The player already owns the backend lifecycle; this bridge only lets common UI issue a direct
 * title/chapter/menu command without threading an engine instance through every composable. The owner
 * identity check prevents an outgoing engine from clearing a newer handover binding.
 */
object ActiveDiscNavigation {
    private data class Binding(
        val owner: Any,
        val backend: DiscNavigationBackend,
    )

    private var binding: Binding? = null

    val isBound: Boolean get() = binding != null

    val navigation: PlaybackDiscNavigationState
        get() = binding?.backend?.navigation ?: PlaybackDiscNavigationState()

    val status: DiscNavigationBackendStatus
        get() = binding?.backend?.status ?: DiscNavigationBackendStatus()

    val menuActive: Boolean
        get() = navigation.menuActive && status.interactiveMenuReady

    internal fun bind(
        owner: Any,
        backend: DiscNavigationBackend,
    ) {
        val previous = binding
        if (previous?.owner !== owner) previous?.backend?.close()
        binding = Binding(owner = owner, backend = backend)
    }

    internal fun unbind(owner: Any) {
        val active = binding ?: return
        if (active.owner === owner) {
            binding = null
            active.backend.close()
        }
    }

    fun selectTitle(index: Int): Boolean = binding?.backend?.selectTitle(index) == true

    fun selectChapter(index: Int): Boolean = binding?.backend?.selectChapter(index) == true

    fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
        val backend = binding?.backend ?: return false
        if (!backend.status.interactiveMenuReady) return false
        return backend.sendMenuCommand(command)
    }

    /** Menu commands are ignored unless a real interactive runtime reports an active menu. */
    fun routeActiveMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
        if (!menuActive) return false
        return sendMenuCommand(command)
    }
}
