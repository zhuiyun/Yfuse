package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

internal val LocalInListPane = compositionLocalOf { false }

private const val MIN_PANE_FRACTION = 0.32f
private const val MAX_PANE_FRACTION = 0.58f
private const val KEYBOARD_PANE_STEP = 0.04f

internal data class DualPaneGeometry(
    val defaultFraction: Float,
    val hingeGap: Dp,
    val bookLocked: Boolean,
)

internal fun resolveDualPaneGeometry(
    posture: WindowPosture,
    foldableMedium: Boolean,
): DualPaneGeometry {
    val bookHingeFraction =
        posture.verticalHingeFraction
            ?.takeIf { posture.isBookMode }
            ?.coerceIn(MIN_PANE_FRACTION, MAX_PANE_FRACTION)
    val defaultFraction =
        bookHingeFraction ?: if (foldableMedium) 0.42f else 0.45f
    val hingeGap =
        if (posture.isBookMode) {
            posture.hingeThicknessDp.coerceAtLeast(0.dp)
        } else {
            0.dp
        }
    return DualPaneGeometry(
        defaultFraction = defaultFraction,
        hingeGap = hingeGap,
        bookLocked = bookHingeFraction != null,
    )
}

/** Keep the nearest browsing destination alongside its selected child in the same NavDisplay. */
internal class ListDetailSceneStrategy<T : Any>(
    private val listKeys: Set<Any>,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T> {
        val top = entries.last()
        val source = entries.dropLast(1).lastOrNull { it.contentKey in listKeys }
        return ListDetailScene(listOfNotNull(source, top), entries.dropLast(1))
    }
}

private data class ListDetailScene<T : Any>(
    override val entries: List<NavEntry<T>>,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    override val key: Any = entries.first().contentKey
    override val content: @Composable () -> Unit = {
        val posture = LocalWindowPosture.current
        val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
        val haptics = LocalHaptics.current
        val palette = LocalPalette.current
        val accent = LocalAccentColors.current.accent

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val totalWidthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
            val foldableMedium = maxWidth < WindowWidthBreakpoints.expanded
            val geometry = resolveDualPaneGeometry(posture, foldableMedium)
            val defaultFraction = geometry.defaultFraction
            var userFraction by remember(defaultFraction) { mutableFloatStateOf(defaultFraction) }
            var draggingDivider by remember { mutableStateOf(false) }

            val hasDetail = entries.size > 1
            val activeFraction = if (geometry.bookLocked) defaultFraction else userFraction
            val targetListFraction = if (hasDetail) activeFraction else 1f
            val animatedListFraction by animateFloatAsState(
                targetValue = targetListFraction,
                animationSpec = if (reduceMotion || draggingDivider) snap() else Motion.settle(),
                label = "listPaneFraction",
            )
            val hingeGap = geometry.hingeGap
            val handleHitWidthPx = with(LocalDensity.current) { 24.dp.toPx() }
            val adjustFraction: (Float) -> Boolean = { delta ->
                val next = (userFraction + delta).coerceIn(MIN_PANE_FRACTION, MAX_PANE_FRACTION)
                val changed = next != userFraction
                if (changed) {
                    userFraction = next
                    haptics.play(HapticSignal.Tick)
                }
                changed
            }
            val resetFraction: () -> Boolean = {
                val changed = userFraction != defaultFraction
                userFraction = defaultFraction
                haptics.play(HapticSignal.Select)
                changed
            }

            Row(Modifier.fillMaxSize()) {
                val primary = entries.first()
                key(primary.contentKey) {
                    Box(Modifier.weight(animatedListFraction.coerceAtLeast(0.01f)).fillMaxHeight().clipToBounds()) {
                        CompositionLocalProvider(LocalInListPane provides hasDetail) {
                            primary.Content()
                        }
                    }
                }
                if (hasDetail) {
                    if (hingeGap > 0.dp) {
                        Box(Modifier.width(hingeGap).fillMaxHeight().background(Color.Black))
                    } else if (geometry.bookLocked) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
                    }
                    val detailEntry = entries[1]
                    val detailWeight = (1f - animatedListFraction).coerceAtLeast(0.01f)
                    Box(Modifier.weight(detailWeight).fillMaxHeight().clipToBounds()) {
                        AnimatedContent(
                            targetState = detailEntry,
                            contentKey = { it.contentKey },
                            transitionSpec = {
                                if (reduceMotion) {
                                    fadeIn(snap()) togetherWith fadeOut(snap())
                                } else {
                                    (
                                        fadeIn(tween(Motion.STANDARD, easing = Motion.Curve)) +
                                            slideInVertically(tween(Motion.STANDARD, easing = Motion.Curve)) { it / 28 }
                                    ) togetherWith fadeOut(tween(Motion.QUICK, easing = Motion.Curve))
                                }
                            },
                            label = "detailPaneContent",
                        ) { activeDetail ->
                            CompositionLocalProvider(LocalInListPane provides false) {
                                activeDetail.Content()
                            }
                        }
                    }
                }
            }
            if (hasDetail && !geometry.bookLocked) {
                val seamOffsetPx = (totalWidthPx * animatedListFraction - handleHitWidthPx / 2f).roundToInt()
                // Overlay the interactive drag handle over the flush seam so no white background gap appears.
                Box(
                    Modifier
                        .offset { IntOffset(seamOffsetPx, 0) }
                        .width(24.dp)
                        .fillMaxHeight()
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> adjustFraction(-KEYBOARD_PANE_STEP)
                                Key.DirectionRight -> adjustFraction(KEYBOARD_PANE_STEP)
                                Key.Enter, Key.Spacebar -> resetFraction()
                                else -> false
                            }
                        }.semantics {
                            contentDescription = "分栏分隔条"
                            stateDescription = "左栏 ${(userFraction * 100).roundToInt()}%"
                            customActions =
                                listOf(
                                    CustomAccessibilityAction("增宽左栏") { adjustFraction(KEYBOARD_PANE_STEP) },
                                    CustomAccessibilityAction("增宽右栏") { adjustFraction(-KEYBOARD_PANE_STEP) },
                                    CustomAccessibilityAction("恢复默认分栏") { resetFraction() },
                                )
                        }.pointerInput(defaultFraction) {
                            detectTapGestures(
                                onDoubleTap = { resetFraction() },
                            )
                        }.pointerInput(totalWidthPx) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    draggingDivider = true
                                    haptics.play(HapticSignal.DragStart)
                                },
                                onDragEnd = { draggingDivider = false },
                                onDragCancel = { draggingDivider = false },
                                onHorizontalDrag = { _, dragAmount ->
                                    val next =
                                        (userFraction + dragAmount / totalWidthPx)
                                            .coerceIn(MIN_PANE_FRACTION, MAX_PANE_FRACTION)
                                    if ((next == MIN_PANE_FRACTION || next == MAX_PANE_FRACTION) &&
                                        next != userFraction
                                    ) {
                                        haptics.play(HapticSignal.Tick)
                                    }
                                    userFraction = next
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(
                                if (palette.isDark) {
                                    Color.White.copy(alpha = if (draggingDivider) 0.34f else 0.14f)
                                } else {
                                    Color.Black.copy(alpha = if (draggingDivider) 0.28f else 0.12f)
                                },
                            ),
                    )
                    Box(
                        Modifier
                            .width(if (draggingDivider) 5.dp else 4.dp)
                            .height(if (draggingDivider) 48.dp else 32.dp)
                            .clip(CircleShape)
                            .background(
                                if (draggingDivider) {
                                    accent.copy(alpha = 0.88f)
                                } else {
                                    Color.White.copy(alpha = 0.42f)
                                },
                            ),
                    )
                }
            }
        }
    }
}
