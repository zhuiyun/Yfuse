package com.yfuse.feature.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
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
import kotlinx.coroutines.flow.first

private const val SEARCH_REVEAL_MS = Motion.SEARCH_REVEAL
private const val SEARCH_WAIT_HALF_CYCLE_MS = Motion.WAIT_HALF_CYCLE

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

/** Only visible result/filter changes advance the handoff; transport flags and typing do not. */
internal fun SearchState.presentationKey(): List<Any?> =
    listOf(
        searchedQuery,
        type,
        serverId,
        libraryId,
        year,
        genre,
        watchStatus,
        sort,
        person,
        people,
        aggregated,
        groups.map { listOf(it.serverId, it.items, it.error) },
        error,
    )

/** Remembers both the phase and the actual presentation so in-place result changes also reveal. */
internal class SearchResultsHandoff(
    initialPhase: SearchResultsPhase,
    initialKey: Any? = null,
) {
    private var committedPhase = initialPhase
    private var committedKey = initialKey

    fun shouldReveal(
        next: SearchResultsPhase,
        moving: Boolean,
        key: Any? = null,
    ): Boolean =
        moving &&
            (next != committedPhase || key != committedKey) &&
            next != SearchResultsPhase.Idle &&
            next != SearchResultsPhase.Loading

    fun committed(
        phase: SearchResultsPhase,
        key: Any? = null,
    ) {
        committedPhase = phase
        committedKey = key
    }

    fun shouldSweep(
        next: SearchResultsPhase,
        moving: Boolean,
        key: Any? = null,
    ): Boolean =
        moving &&
            next == SearchResultsPhase.Results &&
            (committedPhase == SearchResultsPhase.Loading || key != committedKey)
}

internal fun searchRevealProgress(
    progress: Float,
    index: Int,
): Float {
    // Stagger in elapsed time, then ease each row. Easing the shared clock first made
    // nearly every row enter together during the first few frames.
    val delay = index.coerceIn(0, 12) * Motion.SEARCH_ROW_STAGGER.toFloat() / SEARCH_REVEAL_MS
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
    presentationKey: Any? = null,
): SearchRevealMotion {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val enhanced = moving && LocalPulseSweepEnabled.current
    val highlights = enhanced && !LocalAccessibilityOptions.current.reduceTransparency
    val accent = LocalAccentColors.current.accent
    val palette = LocalPalette.current
    val handoff = remember { SearchResultsHandoff(phase, presentationKey) }
    val progress =
        remember(phase, presentationKey, moving) {
            Animatable(if (handoff.shouldReveal(phase, moving, presentationKey)) 0f else 1f)
        }
    // Keep delays across result reordering and style changes, but reset for a new handoff.
    val order = remember(progress) { SearchRevealOrder() }
    val incomingResults =
        remember(phase, presentationKey, moving) { handoff.shouldSweep(phase, moving, presentationKey) }
    val layered = enhanced && incomingResults
    // Update only after a successful composition, including completions while the route is hidden.
    SideEffect { handoff.committed(phase, presentationKey) }
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
    val tension = animateFloatAsState(if (waiting) 1f else 0f, Motion.settle(!moving), label = "search-request-tension")
    LaunchedEffect(waiting) {
        if (!waiting) {
            if (moving) pulse.animateTo(0f, tween(Motion.STANDARD, easing = Motion.Curve)) else pulse.snapTo(0f)
        } else {
            while (true) {
                val durationScale = coroutineContext[MotionDurationScale]
                if (durationScale?.scaleFactor == 0f) {
                    pulse.snapTo(0f)
                    snapshotFlow { durationScale.scaleFactor }.first { it > 0f }
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
            Modifier
                .graphicsLayer {
                    scaleX = 1f - 0.012f * tension.value
                    scaleY = 1f + 0.02f * tension.value
                }.drawWithContent {
                    val release = if (layered) kotlin.math.sin(progress.value * kotlin.math.PI).toFloat() else 0f
                    val split = 52.dp.toPx().coerceAtMost(size.width / 3f)
                    val gap = 5.dp.toPx() * release
                    val radius = size.height / 2f * release
                    val leftLobe =
                        Path().apply {
                            addRoundRect(RoundRect(Rect(0f, 0f, split - gap, size.height), CornerRadius(radius)))
                        }
                    val rightLobe =
                        Path().apply {
                            addRoundRect(
                                RoundRect(Rect(split + gap, 0f, size.width, size.height), CornerRadius(radius)),
                            )
                        }
                    clipPath(leftLobe) {
                        translate(left = -gap) { this@drawWithContent.drawContent() }
                    }
                    clipPath(rightLobe) {
                        translate(left = gap) { this@drawWithContent.drawContent() }
                    }
                }.drawBehind {
                    val rect = Rect(Offset.Zero, size)
                    if (waiting || pulse.value > 0f) {
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
        if (!highlights) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                scaleX = 1f + 0.16f * pulse.value
                scaleY = scaleX
                rotationZ = 8f * pulse.value
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
                    val p = progress.value
                    val front = (size.height + 160.dp.toPx()) * p - 80.dp.toPx()
                    val height = 72.dp.toPx()
                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                listOf(Color.Transparent, palette.card2.copy(alpha = 0.17f), band, Color.Transparent),
                                startY = front - height,
                                endY = front,
                            ),
                        topLeft = Offset(0f, front - height),
                        size = Size(size.width, height),
                    )
                    drawDiagonalSweep(band, p)
                }
            }
        }
    return remember(progress, layered, order, field, icon, page) {
        SearchRevealMotion(progress, layered, order, field, icon, page)
    }
}
