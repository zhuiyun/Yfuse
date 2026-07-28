package com.yfuse.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MiniPlaybackState(
    val active: Boolean = false,
    val title: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Process-local bridge between the dedicated player task and the app shell.
 * The actual engine remains owned by PlayerActivity; this bridge never holds
 * media URLs, tokens or persisted credentials.
 */
object ActivePlayback {
    private val _state = MutableStateFlow(MiniPlaybackState())
    val state = _state.asStateFlow()

    private var toggleAction: (() -> Unit)? = null
    private var openAction: (() -> Unit)? = null
    private var closeAction: (() -> Unit)? = null

    fun bind(toggle: () -> Unit, open: () -> Unit, close: () -> Unit) {
        toggleAction = toggle
        openAction = open
        closeAction = close
    }

    fun update(title: String, playback: PlaybackState) {
        _state.value = MiniPlaybackState(
            active = true,
            title = title,
            playing = playback.playing,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
        )
    }

    fun toggle() = toggleAction?.invoke()
    fun open() = openAction?.invoke()
    fun close() {
        val action = closeAction
        // Hide the long mini controller before finishing the player task. This
        // also prevents its parent tap handler from reopening a closing player.
        clear()
        action?.invoke()
    }

    fun clear() {
        _state.value = MiniPlaybackState()
        toggleAction = null
        openAction = null
        closeAction = null
    }
}
