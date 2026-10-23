package com.yfuse.feature.widgets

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** A single bounded artwork stage: actual neighbouring films, never copies of one poster. */
@Composable
internal fun WidgetKineticLayouts(
    style: WidgetStyle,
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    var position by rememberSaveable(style.id) { mutableFloatStateOf(0f) }
    val lastIndex = items.lastIndex
    val boundedPosition = position.coerceIn(0f, lastIndex.toFloat())
    val currentIndex = boundedPosition.roundToInt().coerceIn(0, lastIndex)
    LaunchedEffect(lastIndex) { position = position.coerceIn(0f, lastIndex.toFloat()) }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val stageWidth = minOf(maxWidth, if (compact) 340.dp else 480.dp)
        val artworkWidth = (stageWidth - 48.dp).coerceAtLeast(1.dp)
        val stageHeight = artworkWidth * kineticStageRatio(style) + 48.dp
        val stepPixels = with(density) { (artworkWidth * .54f).toPx().coerceAtLeast(1f) }
        val copyHeight =
            with(density) {
                AppTypography.body.medium.lineHeight
                    .toDp() * 2 +
                    (
                        if (metadata) {
                            AppTypography.caption.regular.lineHeight
                                .toDp() + 2.dp
                        } else {
                            0.dp
                        }
                    ) + 8.dp
            }.coerceAtLeast(48.dp)
        val scrollState =
            rememberScrollableState { delta ->
                val before = position
                position = (before - delta / stepPixels).coerceIn(0f, lastIndex.toFloat())
                (before - position) * stepPixels
            }
        val fling =
            remember(scrollState, stepPixels, lastIndex, reduceMotion) {
                object : FlingBehavior {
                    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                        val target = kineticTargetIndex(position, initialVelocity, stepPixels, lastIndex)
                        val distance = (position - target) * stepPixels
                        if (reduceMotion) {
                            scrollBy(distance)
                        } else {
                            var previous = 0f
                            animate(
                                initialValue = 0f,
                                targetValue = distance,
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
        val select: (Int) -> Unit = { requested ->
            val target = requested.coerceIn(0, lastIndex)
            scope.launch {
                val delta = (position - target) * stepPixels
                if (reduceMotion) {
                    scrollState.scrollBy(delta)
                } else {
                    scrollState.animateScrollBy(delta, spring(dampingRatio = .94f, stiffness = 340f))
                }
            }
        }

        Column(
            Modifier.width(stageWidth),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stageHeight)
                    .clipToBounds()
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
                            if (direction != 0) select(currentIndex + direction)
                            direction != 0
                        }
                    }.semantics {
                        collectionInfo = CollectionInfo(1, items.size)
                        stateDescription = "第 ${currentIndex + 1} 部，共 ${items.size} 部"
                        if (items.size > 1) {
                            horizontalScrollAxisRange =
                                ScrollAxisRange(
                                    value = { position },
                                    maxValue = { lastIndex.toFloat() },
                                    reverseScrolling = rtl,
                                )
                            scrollBy { x, _ ->
                                if (x == 0f) {
                                    false
                                } else {
                                    select(currentIndex + if (x > 0f) 1 else -1)
                                    true
                                }
                            }
                            customActions =
                                buildList {
                                    if (currentIndex > 0) {
                                        add(
                                            CustomAccessibilityAction("上一部") {
                                                select(currentIndex - 1)
                                                true
                                            },
                                        )
                                    }
                                    if (currentIndex < lastIndex) {
                                        add(
                                            CustomAccessibilityAction("下一部") {
                                                select(currentIndex + 1)
                                                true
                                            },
                                        )
                                    }
                                }
                        }
                    },
            ) {
                val drawingPosition = if (reduceMotion) currentIndex.toFloat() else boundedPosition
                // The farthest depth cover is four places away; at most 11 images are composed.
                val visible = (currentIndex - 5..currentIndex + 5).filter { it in items.indices }
                for (index in visible) {
                    val distance = index - drawingPosition
                    val frame = kineticPosterFrame(style, distance)
                    if (frame.opacity <= .015f) continue
                    val centeredX = if (items.size == 1) (1f - frame.width) / 2 else frame.x
                    val active = index == currentIndex
                    val shape = if (style == WidgetStyle.FoldingGallery) RoundedCornerShape(7.dp) else AppShapes.card
                    key("$index:${items[index].id}") {
                        Box(
                            Modifier
                                .offset(x = 24.dp + artworkWidth * centeredX, y = 24.dp + artworkWidth * frame.y)
                                .width(artworkWidth * frame.width)
                                .height(artworkWidth * frame.height)
                                .zIndex(10f - abs(distance))
                                .graphicsLayer {
                                    rotationY = frame.turn * if (rtl) -1f else 1f
                                    rotationZ = frame.tilt * if (rtl) -1f else 1f
                                    cameraDistance = size.width * 12f
                                    alpha = frame.opacity
                                    this.shape = shape
                                    clip = true
                                    shadowElevation = (if (active) 6.dp else 2.dp).toPx()
                                }.pressable(
                                    label = items[index].title,
                                    onClickLabel = if (active) "查看详情" else "展开影片",
                                    focusShape = shape,
                                    lightFeedback = false,
                                    onClick = { if (active) onOpen(items[index]) else select(index) },
                                ).semantics {
                                    selected = active
                                    stateDescription = if (active) "当前影片" else "第 ${index + 1} 部，共 ${items.size} 部"
                                },
                        ) {
                            Artwork(
                                items[index],
                                Modifier.fillMaxSize().clearAndSetSemantics { },
                                poster = true,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(copyHeight)
                    .pressable(
                        onClickLabel = "查看详情",
                        tintOnPress = true,
                        pressedScale = 1f,
                        lightFeedback = false,
                        onClick = { onOpen(items[currentIndex]) },
                    ).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Crossfade(
                    targetState = items[currentIndex],
                    modifier = Modifier.weight(1f),
                    animationSpec = Motion.tween(if (reduceMotion) 0 else Motion.DISCLOSURE),
                    label = "kineticFilmCopy",
                ) { item ->
                    MediaCopy(item, metadata = metadata, dense = true)
                }
                Box(
                    Modifier.size(36.dp).glass(CircleShape, LocalPalette.current.card, LocalPalette.current.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (rtl) AppIcons.ChevronLeft else AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = LocalAccentColors.current.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (items.size > 1) {
                KineticPositionControls(currentIndex, items.size, boundedPosition, rtl, select)
            }
        }
    }
}

@Composable
private fun KineticPositionControls(
    currentIndex: Int,
    count: Int,
    position: Float,
    rtl: Boolean,
    select: (Int) -> Unit,
) {
    val accent = LocalAccentColors.current.accent
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WidgetIconButton(
            if (rtl) AppIcons.ChevronRight else AppIcons.ChevronLeft,
            "上一部",
            enabled = currentIndex > 0,
        ) { select(currentIndex - 1) }
        Column(
            Modifier.weight(1f).clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "${currentIndex + 1} / $count",
                color = LocalPalette.current.sub2,
                style = AppTypography.caption.regular,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Canvas(Modifier.width(72.dp).height(3.dp)) {
                val y = size.height / 2
                drawLine(accent.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                val length = (size.width / count).coerceAtLeast(12.dp.toPx()).coerceAtMost(size.width)
                val fraction = (position / (count - 1)).coerceIn(0f, 1f)
                val x = (size.width - length) * if (rtl) 1f - fraction else fraction
                drawLine(accent.copy(alpha = .78f), Offset(x, y), Offset(x + length, y), size.height, StrokeCap.Round)
            }
        }
        WidgetIconButton(
            if (rtl) AppIcons.ChevronLeft else AppIcons.ChevronRight,
            "下一部",
            enabled = currentIndex < count - 1,
        ) { select(currentIndex + 1) }
    }
}
