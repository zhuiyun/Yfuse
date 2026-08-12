package com.yfuse.core.designsystem

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** One tab-reselection occurrence, addressed to exactly one tab. */
@Immutable
data class TabReselection(val tabIdentity: String, val occurrence: Long)

internal fun TabReselection.targets(tabIdentity: String?): Boolean =
    tabIdentity != null && this.tabIdentity == tabIdentity

/**
 * Tapping the tab you are already on, plumbed to the page that has to answer it.
 *
 * [TabReselection.tabIdentity] matters while AnimatedContent still composes the outgoing tab:
 * both roots can collect the same flow for one transition frame, but only the addressed one
 * may move. Null outside the shell — the player and any pushed page have no tab bar to tap.
 */
val LocalTabReselected = staticCompositionLocalOf<StateFlow<TabReselection?>?> { null }

/** Stable identity of the tab branch currently providing this composition. */
val LocalTabIdentity = staticCompositionLocalOf<String?> { null }

/**
 * Returns [listState] to the top when the current tab is tapped again.
 *
 * Animated rather than snapped, and deliberately so: the point of the gesture is partly
 * "take me back" and partly "show me where back is", and a page that teleports answers only
 * the first half. Under 减弱动态效果 it snaps, because a long programmatic scroll is exactly
 * the kind of movement that setting exists to remove.
 *
 * The shell clears the replayed value before a real tab switch, so collecting the current
 * event is intentional: a fast second tap remains observable even if the incoming branch's
 * effect starts one frame later.
 */
@Composable
fun ScrollToTopOnReselect(listState: LazyListState) {
    val signal = LocalTabReselected.current ?: return
    val tabIdentity = LocalTabIdentity.current ?: return
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // Effects restart when accessibility options change. Remember the consumed occurrence so
    // StateFlow's replay cannot make an old tap scroll the page a second time.
    var lastHandledOccurrence by rememberSaveable(tabIdentity) { mutableLongStateOf(0L) }
    LaunchedEffect(signal, tabIdentity, listState, reduceMotion) {
        signal.collect { event ->
            if (event == null) {
                // A real tab switch — and a fresh RootComponent after process recreation —
                // clears the event. Reset the saved cursor so a restarted occurrence sequence
                // cannot suppress the first tap in the new process.
                lastHandledOccurrence = 0L
            } else if (event.targets(tabIdentity) &&
                event.occurrence > lastHandledOccurrence
            ) {
                lastHandledOccurrence = event.occurrence
                listState.motionAwareScrollToItem(index = 0, reduceMotion = reduceMotion)
            }
        }
    }
}

/** Grid counterpart to the list overload; same gesture, same reasoning. */
@Composable
fun ScrollToTopOnReselect(gridState: LazyGridState) {
    val signal = LocalTabReselected.current ?: return
    val tabIdentity = LocalTabIdentity.current ?: return
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var lastHandledOccurrence by rememberSaveable(tabIdentity) { mutableLongStateOf(0L) }
    LaunchedEffect(signal, tabIdentity, gridState, reduceMotion) {
        signal.collect { event ->
            if (event == null) {
                lastHandledOccurrence = 0L
            } else if (event.targets(tabIdentity) &&
                event.occurrence > lastHandledOccurrence
            ) {
                lastHandledOccurrence = event.occurrence
                gridState.motionAwareScrollToItem(index = 0, reduceMotion = reduceMotion)
            }
        }
    }
}
