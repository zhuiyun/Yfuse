package com.yfuse.feature.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion

internal enum class SearchResultsPhase {
    Idle,
    Loading,
    Error,
    Empty,
    Results,
}

internal fun SearchState.resultsPhase(): SearchResultsPhase =
    when {
        loading && groups.isEmpty() -> SearchResultsPhase.Loading
        error != null -> SearchResultsPhase.Error
        !hasSearched -> SearchResultsPhase.Idle
        visibleResultCount > 0 -> SearchResultsPhase.Results
        loading -> SearchResultsPhase.Loading
        else -> SearchResultsPhase.Empty
    }

/** Counts presentation changes, never result counts, pages, or server completion updates. */
internal class SearchResultsHandoff(
    initialPhase: SearchResultsPhase,
) {
    private var committedPhase = initialPhase

    fun shouldReveal(
        next: SearchResultsPhase,
        moving: Boolean,
    ): Boolean =
        moving && next != committedPhase && next != SearchResultsPhase.Idle && next != SearchResultsPhase.Loading

    fun committed(phase: SearchResultsPhase) {
        committedPhase = phase
    }
}

/** A shared draw-only fade for the existing lazy items; no outgoing result tree is retained. */
@Composable
internal fun rememberSearchResultsHandoff(phase: SearchResultsPhase): Modifier {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val handoff = remember { SearchResultsHandoff(phase) }
    val progress = remember(phase, moving) { Animatable(if (handoff.shouldReveal(phase, moving)) 0f else 1f) }
    // Update only after a successful composition, including completions while the route is hidden.
    SideEffect { handoff.committed(phase) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f, tween(Motion.STATE_HANDOFF, easing = Motion.Curve))
    }
    return remember(progress) { Modifier.graphicsLayer { alpha = progress.value } }
}
