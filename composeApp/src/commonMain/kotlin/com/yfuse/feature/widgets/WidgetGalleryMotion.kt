package com.yfuse.feature.widgets

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

/** Shared motion only; every gallery still owns its artwork geometry. */
internal class WidgetGalleryMotionState(
    val position: Float,
    val currentIndex: Int,
    val count: Int,
    val rtl: Boolean,
    val reduceMotion: Boolean,
    val inputModifier: Modifier,
    val select: (Int) -> Unit,
    val pitchDegrees: Float = 0f,
)

/** Retain the selected film across refresh/reorder without keeping an invalid array index. */
internal fun galleryRestoredPosition(
    position: Float,
    previousIds: List<String>,
    ids: List<String>,
): Float {
    if (ids.isEmpty()) return 0f
    val finitePosition = position.takeIf(Float::isFinite) ?: 0f
    val previousIndex = finitePosition.roundToInt().coerceIn(0, previousIds.lastIndex.coerceAtLeast(0))
    val selectedId = previousIds.getOrNull(previousIndex)
    val retainedIndex = selectedId?.let(ids::indexOf) ?: -1
    return (if (retainedIndex >= 0) retainedIndex.toFloat() else finitePosition).coerceIn(0f, ids.lastIndex.toFloat())
}

@Composable
internal fun rememberWidgetGalleryMotion(
    styleKey: String,
    items: List<WidgetMedia>,
    stepPixels: Float,
    twoAxis: Boolean = false,
): WidgetGalleryMotionState {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val accent = LocalAccentColors.current.accent
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    var rawPosition by rememberSaveable(styleKey) { mutableFloatStateOf(0f) }
    var pitchDegrees by rememberSaveable(styleKey) { mutableFloatStateOf(0f) }
    var selectionJob by remember(styleKey) { mutableStateOf<Job?>(null) }
    var previousIds by rememberSaveable(styleKey) { mutableStateOf(emptyList<String>()) }
    var focused by remember(styleKey) { mutableStateOf(false) }
    val ids = items.map(WidgetMedia::id)
    val lastIndex = items.lastIndex.coerceAtLeast(0)
    val bounded = (rawPosition.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, lastIndex.toFloat())
    val nearest = bounded.roundToInt().coerceIn(0, lastIndex)
    val renderedPosition = if (reduceMotion && !twoAxis) nearest.toFloat() else bounded
    val current =
        if (twoAxis) {
            prismGalleryFrontIndex(prismGalleryFaces(renderedPosition, items.size, rtl, pitchDegrees))
        } else {
            nearest
        }.coerceIn(0, lastIndex)
    val step = stepPixels.takeIf { it.isFinite() && it > 0f } ?: 1f
    LaunchedEffect(ids) {
        selectionJob?.cancel()
        if (twoAxis && previousIds.isNotEmpty() && previousIds != ids) {
            val selected = prismGalleryFrontIndex(prismGalleryFaces(rawPosition, previousIds.size, rtl, pitchDegrees))
            rawPosition = galleryRestoredPosition(selected.toFloat(), previousIds, ids)
            pitchDegrees = 0f
        } else {
            rawPosition = galleryRestoredPosition(rawPosition, previousIds, ids)
        }
        previousIds = ids
    }
    val scrollState =
        rememberScrollableState { delta ->
            val before = (rawPosition.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, lastIndex.toFloat())
            rawPosition = (before - delta / step).coerceIn(0f, lastIndex.toFloat())
            (before - rawPosition) * step
        }
    val settle:
        (Int, Boolean) -> Unit = { requested, resetRotation ->
            val target = requested.coerceIn(0, lastIndex)
            selectionJob?.cancel()
            selectionJob =
                scope.launch {
                    if (twoAxis && resetRotation) {
                        launch {
                            val start = normalizedPrismPitch(pitchDegrees)
                            if (reduceMotion) {
                                pitchDegrees = 0f
                            } else {
                                animate(
                                    start,
                                    0f,
                                    animationSpec = spring(dampingRatio = .94f, stiffness = 340f),
                                ) { value, _ ->
                                    pitchDegrees = value
                                }
                            }
                        }
                    }
                    val distance = (rawPosition - target) * step
                    if (reduceMotion) {
                        scrollState.scrollBy(distance)
                    } else {
                        scrollState.animateScrollBy(distance, spring(dampingRatio = .94f, stiffness = 340f))
                    }
                }
        }
    val select: (Int) -> Unit = { settle(it, true) }
    val fling =
        remember(scrollState, step, lastIndex, reduceMotion) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val target = kineticTargetIndex(rawPosition, initialVelocity, step, lastIndex)
                    val distance = (rawPosition - target) * step
                    if (reduceMotion) {
                        scrollBy(distance)
                    } else {
                        var previous = 0f
                        animate(
                            0f,
                            distance,
                            animationSpec = spring(dampingRatio = .94f, stiffness = 340f),
                        ) { value, _ ->
                            scrollBy(value - previous)
                            previous = value
                        }
                    }
                    return 0f
                }
            }
        }
    val dragInput =
        if (twoAxis) {
            Modifier.pointerInput(styleKey, step, rtl, lastIndex) {
                val velocity = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        selectionJob?.cancel()
                        velocity.resetTracking()
                    },
                    onDragEnd = {
                        val speed = velocity.calculateVelocity().x * if (rtl) -1f else 1f
                        settle(kineticTargetIndex(rawPosition, speed, step, lastIndex), false)
                    },
                    onDragCancel = { settle(rawPosition.roundToInt(), false) },
                ) { change, amount ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    val horizontal = amount.x * if (rtl) -1f else 1f
                    rawPosition = (rawPosition - horizontal / step).coerceIn(0f, lastIndex.toFloat())
                    pitchDegrees = normalizedPrismPitch(pitchDegrees + amount.y / step * 90f)
                }
            }
        } else {
            Modifier.scrollable(
                state = scrollState,
                orientation = Orientation.Horizontal,
                enabled = items.size > 1,
                reverseDirection = rtl,
                flingBehavior = fling,
            )
        }
    val input =
        dragInput
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (twoAxis && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                    selectionJob?.cancel()
                    pitchDegrees =
                        normalizedPrismPitch(pitchDegrees + if (event.key == Key.DirectionDown) 30f else -30f)
                    true
                } else {
                    val direction =
                        when (event.key) {
                            Key.DirectionRight -> if (rtl) -1 else 1
                            Key.DirectionLeft -> if (rtl) 1 else -1
                            else -> 0
                        }
                    if (direction != 0 && items.size > 1) select(current + direction)
                    direction != 0 && items.size > 1
                }
            }.semantics {
                collectionInfo = CollectionInfo(1, items.size)
                stateDescription =
                    if (items.isEmpty()) {
                        "暂无影片"
                    } else {
                        "第 ${current + 1} 部，共 ${items.size} 部"
                    }
                if (twoAxis) {
                    verticalScrollAxisRange = ScrollAxisRange({ pitchDegrees + 180f }, { 360f })
                }
                if (items.size > 1 || twoAxis) {
                    horizontalScrollAxisRange =
                        ScrollAxisRange({ bounded }, { lastIndex.toFloat() }, reverseScrolling = rtl)
                    scrollBy { x, y ->
                        if (twoAxis && y != 0f) {
                            selectionJob?.cancel()
                            pitchDegrees = normalizedPrismPitch(pitchDegrees + if (y > 0f) 30f else -30f)
                            true
                        } else if (x == 0f || items.size <= 1) {
                            false
                        } else {
                            select(current + if (x > 0f) 1 else -1)
                            true
                        }
                    }
                    customActions =
                        buildList {
                            if (twoAxis) {
                                add(
                                    CustomAccessibilityAction("向上旋转") {
                                        selectionJob?.cancel()
                                        pitchDegrees = normalizedPrismPitch(pitchDegrees - 30f)
                                        true
                                    },
                                )
                                add(
                                    CustomAccessibilityAction("向下旋转") {
                                        selectionJob?.cancel()
                                        pitchDegrees = normalizedPrismPitch(pitchDegrees + 30f)
                                        true
                                    },
                                )
                                add(
                                    CustomAccessibilityAction("复位视角") {
                                        select(current)
                                        true
                                    },
                                )
                            }
                            if (current > 0) {
                                add(
                                    CustomAccessibilityAction("上一部") {
                                        select(current - 1)
                                        true
                                    },
                                )
                            }
                            if (current < lastIndex) {
                                add(
                                    CustomAccessibilityAction("下一部") {
                                        select(current + 1)
                                        true
                                    },
                                )
                            }
                        }
                }
            }.onFocusChanged { focused = it.isFocused }
            .drawWithContent {
                drawContent()
                if (focused) {
                    val inset = 1.dp.toPx()
                    drawRoundRect(
                        color = accent.copy(alpha = .7f),
                        topLeft = Offset(inset, inset),
                        size =
                            Size(
                                (size.width - inset * 2).coerceAtLeast(0f),
                                (size.height - inset * 2).coerceAtLeast(0f),
                            ),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }.focusable()
    return WidgetGalleryMotionState(
        renderedPosition,
        current,
        items.size,
        rtl,
        reduceMotion,
        input,
        select,
        pitchDegrees,
    )
}

/** Share a content-sized footer across the spatial galleries, including large-text fallback. */
@Composable
internal fun WidgetGalleryFooter(
    items: List<WidgetMedia>,
    metadata: Boolean,
    motion: WidgetGalleryMotionState,
    onOpen: (WidgetMedia) -> Unit,
    supportingText: String? = null,
) {
    val item = items.getOrNull(motion.currentIndex) ?: return
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp * fontScale.coerceAtLeast(1f)
        val copy: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier
                    .heightIn(min = 48.dp)
                    .pressable(
                        label = item.title,
                        onClickLabel = "查看详情",
                        pressedScale = 1f,
                        lightFeedback = false,
                        onClick = { onOpen(item) },
                    ).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaCopy(
                    item,
                    metadata = metadata,
                    dense = true,
                    minTitleLines = 1,
                    metadataText = supportingText,
                )
            }
        }
        if (stacked && items.size > 1) {
            Column(Modifier.fillMaxWidth()) {
                copy(Modifier.fillMaxWidth())
                WidgetGalleryPositionControls(motion, Modifier.align(Alignment.End))
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                copy(Modifier.weight(1f))
                if (items.size > 1) WidgetGalleryPositionControls(motion)
            }
        }
    }
}

@Composable
private fun WidgetGalleryPositionControls(
    motion: WidgetGalleryMotionState,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WidgetIconButton(
            if (motion.rtl) AppIcons.ChevronRight else AppIcons.ChevronLeft,
            "上一部",
            motion.currentIndex > 0,
        ) { motion.select(motion.currentIndex - 1) }
        Text(
            "${motion.currentIndex + 1}/${motion.count}",
            modifier = Modifier.clearAndSetSemantics { },
            color = LocalPalette.current.sub2,
            style = AppTypography.caption.regular,
            maxLines = 1,
        )
        WidgetIconButton(
            if (motion.rtl) AppIcons.ChevronLeft else AppIcons.ChevronRight,
            "下一部",
            motion.currentIndex < motion.count - 1,
        ) { motion.select(motion.currentIndex + 1) }
    }
}
