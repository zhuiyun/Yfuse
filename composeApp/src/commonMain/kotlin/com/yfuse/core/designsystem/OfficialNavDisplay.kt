package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent

/** AndroidX Navigation 3 host with an edge-reveal back transition and no scale or fade. */
@Composable
fun <T : Any> OfficialNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    contentKey: (T) -> String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val parentRouteVisible = LocalRouteVisible.current
    val currentContent by rememberUpdatedState(content)
    val currentTop by rememberUpdatedState(backStack.last())
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = onBack,
        popTransitionSpec = {
            edgeRevealBackTransition(NavigationEvent.EDGE_LEFT)
        },
        predictivePopTransitionSpec = { swipeEdge ->
            edgeRevealBackTransition(swipeEdge)
        },
        entryProvider = { key ->
            NavEntry(
                key = key,
                contentKey = contentKey(key),
            ) { entryKey ->
                CompositionLocalProvider(
                    LocalRouteVisible provides (parentRouteVisible && entryKey == currentTop),
                ) {
                    currentContent(entryKey)
                }
            }
        },
    )
}

private fun edgeRevealBackTransition(swipeEdge: Int): ContentTransform = ContentTransform(
    targetContentEnter = EnterTransition.None,
    initialContentExit = slideOutHorizontally(
        targetOffsetX = { fullWidth -> backExitOffset(fullWidth, swipeEdge) },
    ),
    // Keep the previous destination below the opaque outgoing page so only the uncovered
    // edge is visible as the gesture progresses.
    targetContentZIndex = -1f,
)

internal fun backExitOffset(fullWidth: Int, swipeEdge: Int): Int = when (swipeEdge) {
    NavigationEvent.EDGE_RIGHT -> -fullWidth
    else -> fullWidth
}
