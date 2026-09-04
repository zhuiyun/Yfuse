package com.yfuse.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a player is on screen and owning playback, picture-in-picture included.
 *
 * Entering PiP hands the full-screen slot back to whatever was underneath, so MainActivity is
 * started again and reclaims app-foreground - which resumes sixty-second health probing across
 * every saved server and every address each of them has, plus library sync, on the exact
 * connection the PiP window is still streaming over. Backgrounding is keyed on MainActivity's
 * own lifecycle, which cannot see that, so the player publishes it here instead.
 */
internal object PlayerForegroundRegistry {
    private val _visible = MutableStateFlow(false)

    /** True from the moment a player becomes visible until it is no longer on screen at all. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun setVisible(value: Boolean) {
        _visible.value = value
    }
}
