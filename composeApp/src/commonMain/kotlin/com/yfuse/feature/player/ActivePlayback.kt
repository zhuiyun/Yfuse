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
    private data class Actions(
        val toggle: () -> Unit,
        val open: () -> Unit,
        val close: () -> Unit,
    )

    private val _state = MutableStateFlow(MiniPlaybackState())
    val state = _state.asStateFlow()

    /** One immutable binding snapshot, so callbacks can never come from different owners. */
    private var actions: Actions? = null

    fun bind(
        toggle: () -> Unit,
        open: () -> Unit,
        close: () -> Unit,
    ) {
        actions = Actions(toggle = toggle, open = open, close = close)
    }

    fun update(
        title: String,
        playback: PlaybackState,
    ) {
        _state.value =
            MiniPlaybackState(
                active = true,
                title = title,
                playing = playback.playing,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
            )
    }

    fun toggle() = actions?.toggle?.invoke()

    fun open() = actions?.open?.invoke()

    fun close() {
        val close = actions?.close
        // Hide the mini controller before finishing the player task. Capturing the callback before
        // clear keeps this operation tied to the same immutable binding snapshot.
        clear()
        close?.invoke()
    }

    fun clear() {
        _state.value = MiniPlaybackState()
        actions = null
    }
}
