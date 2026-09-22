package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local binding for the disc-navigation popup and platform remote input.
 *
 * The player already owns the backend lifecycle; this bridge only lets common UI issue a direct
 * title/chapter/angle/menu command without threading an engine instance through every composable. The
 * owner identity check prevents an outgoing engine from clearing a newer handover binding.
 */
object ActiveDiscNavigation {
    private data class Binding(
        val owner: Any,
        val backend: DiscNavigationBackend,
    )

    private var binding: Binding? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

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
        if (previous?.backend !== backend) {
            previous?.backend?.setChangeListener(null)
            previous?.backend?.close()
        }
        binding = Binding(owner = owner, backend = backend)
        backend.setChangeListener {
            if (binding?.backend === backend) bumpRevision()
        }
        bumpRevision()
    }

    internal fun unbind(owner: Any) {
        val active = binding ?: return
        if (active.owner === owner) {
            binding = null
            active.backend.setChangeListener(null)
            active.backend.close()
            bumpRevision()
        }
    }

    fun selectTitle(index: Int): Boolean = binding?.backend?.selectTitle(index) == true

    fun selectChapter(index: Int): Boolean = binding?.backend?.selectChapter(index) == true

    fun selectAngle(index: Int): Boolean = binding?.backend?.selectAngle(index) == true

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

    /** Touch coordinates are accepted only while the authored interactive plane is visible. */
    fun routeActiveMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean {
        val backend = binding?.backend ?: return false
        if (!menuActive || !backend.status.interactiveMenuReady) return false
        return backend.selectMenuPoint(x, y, activate)
    }

    private fun bumpRevision() {
        mutableRevision.value =
            if (mutableRevision.value == Long.MAX_VALUE) 0L else mutableRevision.value + 1L
    }
}
