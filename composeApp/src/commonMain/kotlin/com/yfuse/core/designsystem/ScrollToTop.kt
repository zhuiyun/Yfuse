package com.yfuse.core.designsystem

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop

/**
 * Tapping the tab you are already on, plumbed to the page that has to answer it.
 *
 * The signal is a counter published by the app shell; only the active tab's root screen is
 * composed, so a single app-wide channel reaches exactly the one page that should react. Null
 * outside the shell — the player and any pushed page have no tab bar to be tapped.
 */
val LocalTabReselected = staticCompositionLocalOf<StateFlow<Int>?> { null }

/**
 * Returns [listState] to the top when the current tab is tapped again.
 *
 * Animated rather than snapped, and deliberately so: the point of the gesture is partly
 * "take me back" and partly "show me where back is", and a page that teleports answers only
 * the first half. Under 减弱动态效果 it snaps, because a long programmatic scroll is exactly
 * the kind of movement that setting exists to remove.
 *
 * `drop(1)` skips the counter's current value — collecting a [StateFlow] replays it, and
 * without this every tab switch would scroll the incoming page to the top on arrival.
 */
@Composable
fun ScrollToTopOnReselect(listState: LazyListState) {
    val signal = LocalTabReselected.current ?: return
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    LaunchedEffect(signal, listState, reduceMotion) {
        signal.drop(1).collect {
            if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
    }
}
