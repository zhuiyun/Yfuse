package com.yfuse.feature.widgets

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
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
): WidgetGalleryMotionState {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val accent = LocalAccentColors.current.accent
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    var rawPosition by rememberSaveable(styleKey) { mutableFloatStateOf(0f) }
    var previousIds by rememberSaveable(styleKey) { mutableStateOf(emptyList<String>()) }
    var focused by remember(styleKey) { mutableStateOf(false) }
    val ids = items.map(WidgetMedia::id)
    val lastIndex = items.lastIndex.coerceAtLeast(0)
    val bounded = (rawPosition.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, lastIndex.toFloat())
    val current = bounded.roundToInt().coerceIn(0, lastIndex)
    val step = stepPixels.takeIf { it.isFinite() && it > 0f } ?: 1f
    LaunchedEffect(ids) {
        rawPosition = galleryRestoredPosition(rawPosition, previousIds, ids)
        previousIds = ids
    }
    val scrollState =
        rememberScrollableState { delta ->
            val before = (rawPosition.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, lastIndex.toFloat())
            rawPosition = (before - delta / step).coerceIn(0f, lastIndex.toFloat())
            (before - rawPosition) * step
        }
    val select: (Int) -> Unit = { requested ->
        val target = requested.coerceIn(0, lastIndex)
        scope.launch {
            val distance = (rawPosition - target) * step
            if (reduceMotion) {
                scrollState.scrollBy(
                    distance,
                )
            } else {
                scrollState.animateScrollBy(distance, spring(dampingRatio = .94f, stiffness = 340f))
            }
        }
    }
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
    val input =
        Modifier
            .scrollable(
                state = scrollState,
                orientation = Orientation.Horizontal,
                enabled = items.size > 1,
                reverseDirection = rtl,
                flingBehavior = fling,
            ).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || items.size <= 1) {
                    false
                } else {
                    val direction =
                        when (event.key) {
                            Key.DirectionRight -> if (rtl) -1 else 1
                            Key.DirectionLeft -> if (rtl) 1 else -1
                            else -> 0
                        }
                    if (direction != 0) select(current + direction)
                    direction != 0
                }
            }.semantics {
                collectionInfo = CollectionInfo(1, items.size)
                stateDescription =
                    if (items.isEmpty()) {
                        "暂无影片"
                    } else {
                        "第 ${current + 1} 部，共 ${items.size} 部"
                    }
                if (items.size > 1) {
                    horizontalScrollAxisRange =
                        ScrollAxisRange({ bounded }, { lastIndex.toFloat() }, reverseScrolling = rtl)
                    scrollBy { x, _ ->
                        if (x == 0f) {
                            false
                        } else {
                            select(current + if (x > 0f) 1 else -1)
                            true
                        }
                    }
                    customActions =
                        buildList {
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
        if (reduceMotion) current.toFloat() else bounded,
        current,
        items.size,
        rtl,
        reduceMotion,
        input,
        select,
    )
}

@Composable
internal fun WidgetGalleryFooter(
    items: List<WidgetMedia>,
    metadata: Boolean,
    motion: WidgetGalleryMotionState,
    onOpen: (WidgetMedia) -> Unit,
) {
    val item = items.getOrNull(motion.currentIndex) ?: return
    val accent = LocalAccentColors.current.accent
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(
                    label = item.title,
                    onClickLabel = "查看详情",
                    pressedScale = 1f,
                    lightFeedback = false,
                    onClick = { onOpen(item) },
                ).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaCopy(item, Modifier.weight(1f), metadata = metadata, dense = true)
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (motion.rtl) AppIcons.ChevronLeft else AppIcons.ChevronRight,
                    null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (items.size > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WidgetIconButton(
                    if (motion.rtl) AppIcons.ChevronRight else AppIcons.ChevronLeft,
                    "上一部",
                    motion.currentIndex > 0,
                ) { motion.select(motion.currentIndex - 1) }
                Column(
                    Modifier.weight(1f).clearAndSetSemantics {
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement =
                        Arrangement.spacedBy(
                            5.dp,
                        ),
                ) {
                    Text(
                        "${motion.currentIndex + 1} / ${items.size}",
                        color = LocalPalette.current.sub2,
                        style = AppTypography.caption.regular,
                        maxLines = 1,
                    )
                    Canvas(Modifier.width(80.dp).height(3.dp)) {
                        val y = size.height / 2
                        drawLine(
                            accent.copy(alpha = .15f),
                            Offset(0f, y),
                            Offset(size.width, y),
                            size.height,
                            StrokeCap.Round,
                        )
                        val length = (size.width / items.size).coerceIn(12.dp.toPx(), size.width)
                        val fraction = motion.position / (items.size - 1)
                        val x = (size.width - length) * if (motion.rtl) 1 - fraction else fraction
                        drawLine(accent, Offset(x, y), Offset(x + length, y), size.height, StrokeCap.Round)
                    }
                }
                WidgetIconButton(
                    if (motion.rtl) AppIcons.ChevronLeft else AppIcons.ChevronRight,
                    "下一部",
                    motion.currentIndex < items.lastIndex,
                ) { motion.select(motion.currentIndex + 1) }
            }
        }
    }
}
