package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where the player's close key sits in its window, so the departing artwork can shrink into it.
 *
 * The last measured ring is kept even after the chrome auto-hides: the key does not move, and a
 * departure that starts from the system back gesture with the chrome down still needs somewhere
 * to go. The reader validates the rectangle against its own bounds before trusting it.
 */
internal object PlayerCloseAnchor {
    var bounds: Rect? by mutableStateOf(null)
        private set

    fun update(rect: Rect) {
        if (rect.width > 0f && rect.height > 0f) bounds = rect
    }
}

/** Publishes the visible ring of the close key, in window root coordinates. */
@Composable
internal fun Modifier.playerCloseAnchor(): Modifier =
    onGloballyPositioned { coordinates -> PlayerCloseAnchor.update(coordinates.boundsInRoot()) }
