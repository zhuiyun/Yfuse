package com.yfuse.feature.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalPulseSweepEnabled
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.drawDiagonalSweep
import com.yfuse.core.designsystem.drawMotionSweep
import kotlinx.coroutines.delay

private const val SEARCH_REVEAL_MS = 680
private const val SEARCH_WAIT_HALF_CYCLE_MS = 850

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

    fun shouldSweep(
        next: SearchResultsPhase,
        moving: Boolean,
    ): Boolean = moving && committedPhase == SearchResultsPhase.Loading && next == SearchResultsPhase.Results
}

internal fun searchRevealProgress(
    progress: Float,
    index: Int,
): Float {
    // Stagger in elapsed time, then ease each row. Easing the shared clock first made
    // nearly every row enter together during the first few frames.
    val delay = index.coerceIn(0, 5) * 0.07f
    val localProgress = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
    return Motion.Curve.transform(localProgress)
}

/** Retains each visible result's first delay until this handoff finishes. */
internal class SearchRevealOrder {
    private val indices = mutableMapOf<String, Int>()

    fun progress(
        progress: Float,
        key: String,
        index: Int,
    ): Float {
        if (progress >= 1f) {
            indices.clear()
            return 1f
        }
        return searchRevealProgress(progress, indices.getOrPut(key) { index })
    }
}

internal class SearchRevealMotion(
    private val progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    private val layered: Boolean,
    private val order: SearchRevealOrder,
    val field: Modifier,
    val icon: Modifier,
    /** For the whole results page: the same pulse and sweep the field carries, at page scale. */
    val page: Modifier,
) {
    fun item(
        index: Int = 0,
        key: String? = null,
    ): Modifier =
        Modifier.graphicsLayer {
            val elapsed = progress.value
            val amount =
                when {
                    !layered -> elapsed
                    key != null -> order.progress(elapsed, key, index)
                    else -> searchRevealProgress(elapsed, index)
                }
            alpha = amount
            translationY = if (layered) 18.dp.toPx() * (1f - amount) else 0f
        }
}

/** One clock for the visible lazy items: later pages never create their own entry animations. */
@Composable
internal fun rememberSearchResultsHandoff(
    phase: SearchResultsPhase,
    loading: Boolean = phase == SearchResultsPhase.Loading,
): SearchRevealMotion {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val enhanced = moving && LocalPulseSweepEnabled.current
    val highlights = enhanced && !LocalAccessibilityOptions.current.reduceTransparency
    val accent = LocalAccentColors.current.accent
    val palette = LocalPalette.current
    val handoff = remember { SearchResultsHandoff(phase) }
    val progress = remember(phase, moving) { Animatable(if (handoff.shouldReveal(phase, moving)) 0f else 1f) }
    // Keep delays across result reordering and style changes, but reset for a new handoff.
    val order = remember(progress) { SearchRevealOrder() }
    val incomingResults = remember(phase, moving) { handoff.shouldSweep(phase, moving) }
    val layered = enhanced && incomingResults
    // Update only after a successful composition, including completions while the route is hidden.
    SideEffect { handoff.committed(phase) }
    LaunchedEffect(progress, enhanced) {
        if (progress.value < 1f) {
            val animation =
                if (layered) {
                    tween<Float>(SEARCH_REVEAL_MS, easing = LinearEasing)
                } else {
                    tween(Motion.STATE_HANDOFF, easing = Motion.Curve)
                }
            progress.animateTo(1f, animation)
        }
    }
    val pulse = remember { Animatable(0f) }
    // Refreshing the same query retains Results; it still needs visible request feedback.
    val waiting = highlights && loading
    LaunchedEffect(waiting) {
        pulse.snapTo(0f)
        if (waiting) {
            while (true) {
                if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
                    pulse.snapTo(0f)
                    return@LaunchedEffect
                }
                pulse.animateTo(1f, tween(SEARCH_WAIT_HALF_CYCLE_MS, easing = Motion.Curve))
                pulse.animateTo(0f, tween(SEARCH_WAIT_HALF_CYCLE_MS, easing = Motion.Curve))
                delay(16)
            }
        }
    }
    val field =
        if (!highlights) {
            Modifier
        } else {
            Modifier.drawBehind {
                val rect = Rect(Offset.Zero, size)
                if (waiting) {
                    drawRoundRect(
                        brush =
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.20f + 0.12f * pulse.value), Color.Transparent),
                                center = Offset(size.width * (0.15f + pulse.value * 0.60f), size.height * 0.5f),
                                radius = (size.width * 0.65f).coerceAtLeast(1f),
                            ),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                    val inset = 1.dp.toPx()
                    drawRoundRect(
                        brush =
                            Brush.linearGradient(
                                listOf(
                                    accent.copy(alpha = 0.12f),
                                    accent.copy(alpha = 0.72f),
                                    accent.copy(alpha = 0.12f),
                                ),
                                start = Offset(size.width * (pulse.value - 0.5f), 0f),
                                end = Offset(size.width * (pulse.value + 0.5f), size.height),
                            ),
                        topLeft = Offset(inset, inset),
                        size =
                            Size(
                                (size.width - inset * 2f).coerceAtLeast(0f),
                                (size.height - inset * 2f).coerceAtLeast(0f),
                            ),
                        cornerRadius = CornerRadius((size.height / 2f - inset).coerceAtLeast(0f)),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
                if (layered) drawMotionSweep(rect, accent, progress.value, alpha = 1.7f)
            }
        }
    val icon =
        if (!waiting) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                scaleX = 1.05f + 0.16f * pulse.value
                scaleY = scaleX
                rotationZ = -8f + 16f * pulse.value
            }
        }
    // The design's sweep crosses the page, not just the field. While the request runs, a
    // faint accent bloom drifts across the whole page on the same beat as the field's pulse;
    // when the results land, one diagonal band of light crosses the page over them.
    val page =
        if (!highlights) {
            Modifier
        } else {
            Modifier.drawWithContent {
                drawContent()
                if (waiting) {
                    drawRect(
                        brush =
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.07f + 0.04f * pulse.value), Color.Transparent),
                                center =
                                    Offset(
                                        size.width * (0.2f + 0.6f * pulse.value),
                                        size.height * (0.35f + 0.2f * pulse.value),
                                    ),
                                radius = (size.width * 0.9f).coerceAtLeast(1f),
                            ),
                    )
                }
                if (layered) {
                    val band = if (palette.isDark) Color.White.copy(alpha = 0.08f) else accent.copy(alpha = 0.10f)
                    drawDiagonalSweep(band, progress.value)
                }
            }
        }
    return remember(progress, layered, order, field, icon, page) {
        SearchRevealMotion(progress, layered, order, field, icon, page)
    }
}
