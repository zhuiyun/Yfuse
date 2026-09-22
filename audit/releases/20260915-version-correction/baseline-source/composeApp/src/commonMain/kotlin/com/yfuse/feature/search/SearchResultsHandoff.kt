package com.yfuse.feature.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPulseSweepEnabled
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.drawDiagonalSweep
import com.yfuse.core.designsystem.rememberSkeletonSweepReader
import com.yfuse.core.designsystem.skeletonSweepBand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How long one result row takes to float in, and the step between neighbouring rows. */
internal const val SEARCH_ROW_MS = Motion.ARRIVAL_REVEAL
internal const val SEARCH_ROW_STAGGER_MS = Motion.SEARCH_ROW_STAGGER
private const val SEARCH_STAGGER_LIMIT = 12

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

/**
 * Remembers the last committed phase and presentation, so the screen can tell a search
 * landing from a change to the page it already shows.
 */
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

    /** A different phase replaces what is on the page; the same phase with a new key only adds to it. */
    fun isFresh(next: SearchResultsPhase): Boolean = next != committedPhase

    fun committed(
        phase: SearchResultsPhase,
        key: Any? = null,
    ) {
        committedPhase = phase
        committedKey = key
    }

    /**
     * The page sweep belongs to a search landing from its skeleton.
     * Later servers, more pages and filter changes add rows; they do not replay the landing.
     */
    fun shouldSweep(
        next: SearchResultsPhase,
        moving: Boolean,
    ): Boolean = moving && next == SearchResultsPhase.Results && committedPhase == SearchResultsPhase.Loading
}

/** One row's entry: wait for its slot in elapsed time, then ease over [rowMs]. */
internal fun searchRowProgress(
    elapsedMs: Float,
    slot: Int,
    rowMs: Int = SEARCH_ROW_MS,
    staggered: Boolean = true,
): Float {
    val delay = if (staggered) slot.coerceIn(0, SEARCH_STAGGER_LIMIT) * SEARCH_ROW_STAGGER_MS else 0
    return Motion.Curve.transform(((elapsedMs - delay) / rowMs).coerceIn(0f, 1f))
}

/**
 * One opening of the reveal. Rows first seen while it runs enter on its clock, in the
 * order they are first drawn; rows the page already showed are not touched.
 */
internal class SearchRevealBatch(
    val startMs: Float,
    val rowMs: Int = SEARCH_ROW_MS,
    val staggered: Boolean = true,
    val lift: Boolean = staggered,
    /** Where the page band starts, or null when this batch adds rows without a landing sweep. */
    val sweepFrom: Float? = null,
) {
    private var slots = 0

    fun nextSlot(): Int = if (staggered) slots++ else 0

    val endMs: Float
        get() = startMs + (if (staggered) SEARCH_STAGGER_LIMIT * SEARCH_ROW_STAGGER_MS else 0) + rowMs
}

/**
 * Which batch each row entered with. A row keeps its batch and slot for as long as it
 * stays on the page, so results reordering under it or arriving after it cannot fade it
 * back out; a fresh phase forgets every row so the replacement page enters as a whole.
 */
internal class SearchRevealSchedule {
    private class Row(
        val batch: SearchRevealBatch?,
        val slot: Int,
    )

    private val rows = HashMap<String, Row>()
    private var open: SearchRevealBatch? = null

    fun open(
        batch: SearchRevealBatch,
        fresh: Boolean,
    ) {
        if (fresh) rows.clear()
        open = batch
    }

    val endMs: Float
        get() = open?.endMs ?: 0f

    fun progress(
        key: String,
        elapsedMs: Float,
    ): Float {
        val row = rows.getOrPut(key) { open.let { Row(it, it?.nextSlot() ?: 0) } }
        val batch = row.batch ?: return 1f
        return searchRowProgress(elapsedMs - batch.startMs, row.slot, batch.rowMs, batch.staggered)
    }

    fun lifts(key: String): Boolean = rows[key]?.batch?.lift == true
}

internal class SearchRevealMotion(
    private val schedule: SearchRevealSchedule,
    private val clock: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val field: Modifier,
    val icon: Modifier,
    /** For the whole results page: the request bloom and, on a landing, the one band of light. */
    val page: Modifier,
) {
    /** [key] is the row's identity across result changes; [index] only names rows that have none. */
    fun item(
        index: Int = 0,
        key: String? = null,
    ): Modifier {
        val identity = key ?: "index:$index"
        return Modifier.graphicsLayer {
            val amount = schedule.progress(identity, clock.value)
            alpha = amount
            translationY = if (schedule.lifts(identity)) 18.dp.toPx() * (1f - amount) else 0f
        }
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
    val band = skeletonSweepBand()
    val skeletonSweep = rememberSkeletonSweepReader()
    val handoff = remember { SearchResultsHandoff(phase, presentationKey) }
    // Policy changes discard the schedule; returning to the route reveals nothing again.
    val schedule = remember(moving) { SearchRevealSchedule() }
    val clock = remember(schedule) { Animatable(0f) }
    val sweep = remember(schedule) { Animatable(1f) }
    // One batch per presentation change. Rows already on the page keep their opacity, only the
    // rows the change adds enter, and only a landing from the skeleton sweeps the page. The
    // band continues from wherever the skeleton's band is, so the light never jumps.
    val batch =
        remember(phase, presentationKey, moving) {
            if (!handoff.shouldReveal(phase, moving, presentationKey)) return@remember null
            val rows = phase == SearchResultsPhase.Results
            val sweeping = enhanced && handoff.shouldSweep(phase, moving)
            SearchRevealBatch(
                startMs = Snapshot.withoutReadObservation { clock.value },
                rowMs = if (rows) SEARCH_ROW_MS else Motion.STATE_HANDOFF,
                staggered = rows,
                lift = rows,
                sweepFrom = if (sweeping) skeletonSweep().coerceAtLeast(0f) else null,
            ).also { schedule.open(it, fresh = handoff.isFresh(phase)) }
        }
    // Update only after a successful composition, including completions while the route is hidden.
    SideEffect { handoff.committed(phase, presentationKey) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(batch) {
        batch ?: return@LaunchedEffect
        batch.sweepFrom?.let { from ->
            // Outlives this effect: a batch that lands while the band is crossing must not freeze it.
            scope.launch {
                sweep.snapTo(from)
                sweep.animateTo(1f, tween(((1f - from) * Motion.SEARCH_REVEAL).roundToInt(), easing = LinearEasing))
            }
        }
        val end = batch.endMs
        if (clock.value < end) {
            clock.animateTo(end, tween((end - clock.value).roundToInt(), easing = LinearEasing))
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
                pulse.animateTo(1f, tween(Motion.WAIT_HALF_CYCLE, easing = Motion.Curve))
                pulse.animateTo(0f, tween(Motion.WAIT_HALF_CYCLE, easing = Motion.Curve))
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
                }.drawBehind {
                    if (!waiting && pulse.value <= 0f) return@drawBehind
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
    // While the request runs, a faint accent bloom drifts across the page on the same beat as
    // the field's pulse. When the results land, the skeleton's diagonal band finishes its
    // crossing over them: one band, the same one, not a second light.
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
                val p = sweep.value
                if (p > 0f && p < 1f) drawDiagonalSweep(band, p)
            }
        }
    // The wrapper was rebuilt on every recomposition of the screen, so every row reading it
    // got a fresh object — and a fresh [item] modifier — for changes that had nothing to do
    // with the reveal. Each part it holds is already a new instance only when its own inputs
    // change, so the same keys are enough to make the wrapper as stable as its contents.
    return remember(schedule, clock, field, icon, page) {
        SearchRevealMotion(schedule, clock, field, icon, page)
    }
}
