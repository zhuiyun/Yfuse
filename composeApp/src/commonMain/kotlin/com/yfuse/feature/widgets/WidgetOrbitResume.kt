package com.yfuse.feature.widgets

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.semantics.Role
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
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.yfuse.core.designsystem.ThemeText as Text

/** A bounded orbit; the circle and its satellites always refer to actual source items. */
@Composable
internal fun WidgetOrbitResume(
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val density = LocalDensity.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    var position by rememberSaveable { mutableFloatStateOf(0f) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val lastIndex = items.lastIndex
    val boundedPosition = position.coerceIn(0f, lastIndex.toFloat())
    val currentIndex = boundedPosition.roundToInt().coerceIn(items.indices)
    val item = items[currentIndex]
    val ids = items.map { it.id }
    LaunchedEffect(ids) {
        val target = orbitAnchorIndex(selectedId, position.roundToInt(), items)
        position = target.toFloat()
        selectedId = items[target].id
    }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val width =
            widgetGalleryWidth(
                maxWidth,
                compact,
                LocalWidgetGalleryMaxHeight.current,
                .80f,
                widthInset = 32.dp,
                heightInset = 24.dp,
            )
        val artWidth = (width - 32.dp).coerceAtLeast(1.dp)
        val stageHeight = artWidth * .80f + 24.dp
        val stepPixels = with(density) { (artWidth * .62f).toPx().coerceAtLeast(1f) }
        val scroll =
            rememberScrollableState { delta ->
                val before = position
                position = (before - delta / stepPixels).coerceIn(0f, lastIndex.toFloat())
                selectedId = items[position.roundToInt().coerceIn(items.indices)].id
                (before - position) * stepPixels
            }
        val fling =
            remember(scroll, stepPixels, lastIndex, reduceMotion) {
                object : FlingBehavior {
                    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                        val target = kineticTargetIndex(position, initialVelocity, stepPixels, lastIndex)
                        val distance = (position - target) * stepPixels
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
        val select: (Int) -> Unit = { requested ->
            val target = requested.coerceIn(items.indices)
            scope.launch {
                val delta = (position - target) * stepPixels
                if (reduceMotion) {
                    scroll.scrollBy(delta)
                } else {
                    scroll.animateScrollBy(delta, spring(dampingRatio = .94f, stiffness = 340f))
                }
            }
        }
        Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stageHeight)
                    .clipToBounds()
                    .scrollable(
                        scroll,
                        Orientation.Horizontal,
                        enabled = items.size > 1,
                        reverseDirection = rtl,
                        flingBehavior = fling,
                    ).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || items.size < 2) {
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
                            horizontalScrollAxisRange = ScrollAxisRange({ position }, { lastIndex.toFloat() }, rtl)
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
                                    if (currentIndex >
                                        0
                                    ) {
                                        add(
                                            CustomAccessibilityAction("上一部") {
                                                select(currentIndex - 1)
                                                true
                                            },
                                        )
                                    }
                                    if (currentIndex <
                                        lastIndex
                                    ) {
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
                val neighbours = orbitNeighbourIndices(currentIndex, items.size)
                if (neighbours.isNotEmpty()) {
                    Canvas(Modifier.offset(x = 16.dp, y = 12.dp).width(artWidth).height(artWidth * .80f)) {
                        drawOval(
                            color = accent.copy(alpha = .16f),
                            topLeft = Offset(size.width * .04f, size.height * .055f),
                            size = Size(size.width * .92f, size.height * .88f),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                }
                val primaryFrame = orbitPrimaryFrame(neighbours.isEmpty())
                val circleSize = artWidth * primaryFrame.width
                Box(
                    Modifier
                        .offset(x = 16.dp + artWidth * primaryFrame.x, y = 12.dp + artWidth * primaryFrame.y)
                        .size(circleSize)
                        .pressable(
                            label = item.title,
                            role = Role.Button,
                            onClickLabel = "查看详情",
                            focusShape = CircleShape,
                            pressedScale = .98f,
                            lightFeedback = false,
                            onClick = { onOpen(item) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        item,
                        animationSpec = Motion.tween(if (reduceMotion) 0 else Motion.DISCLOSURE),
                        label = "orbitFilm",
                    ) { film ->
                        key(film.id) {
                            OrbitPrimaryArtwork(
                                film,
                                metadata && density.fontScale <= 1.4f && circleSize >= 140.dp,
                                Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                neighbours.forEachIndexed { slot, neighbourIndex ->
                    val neighbour = items[neighbourIndex]
                    val frame = orbitSatelliteFrame(slot)
                    val tilt = if (reduceMotion) frame.tilt else frame.tilt + (boundedPosition - currentIndex) * 8f
                    Box(
                        Modifier
                            .offset(x = 16.dp + artWidth * frame.x, y = 12.dp + artWidth * frame.y)
                            .width(artWidth * frame.width)
                            .height(artWidth * frame.height)
                            .graphicsLayer {
                                rotationZ = tilt * if (rtl) -1f else 1f
                                shape = AppShapes.thumb
                                clip = true
                                shadowElevation = 3.dp.toPx()
                            }.pressable(
                                label = neighbour.title,
                                role = Role.Button,
                                onClickLabel = "切换到此影片",
                                focusShape = AppShapes.thumb,
                                lightFeedback = false,
                                onClick = { select(neighbourIndex) },
                            ),
                    ) {
                        Artwork(neighbour, Modifier.fillMaxSize().clearAndSetSemantics { }, poster = true)
                        val progress = orbitProgress(neighbour)
                        if (progress != null) {
                            Canvas(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(6.dp)
                                    .height(3.dp),
                            ) {
                                val y = size.height / 2
                                drawLine(
                                    palette.background.copy(alpha = .65f),
                                    Offset(0f, y),
                                    Offset(size.width, y),
                                    size.height,
                                    StrokeCap.Round,
                                )
                                if (progress >
                                    0f
                                ) {
                                    drawLine(
                                        accent,
                                        Offset(0f, y),
                                        Offset(size.width * progress, y),
                                        size.height,
                                        StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val progress = orbitProgress(item)
            val remaining = orbitRemainingMinutes(item)
            val details =
                listOfNotNull(
                    orbitEpisodeLabel(item).takeIf(String::isNotBlank),
                    remaining?.let { "约剩 $it 分钟" }
                        ?: when {
                            progress == 1f -> "已看完"
                            progress != null && progress > 0f -> "已看 ${(progress * 100).toInt()}%"
                            else -> item.year.take(4).takeIf(String::isNotBlank)
                        },
                ).joinToString(" · ")
            WidgetGalleryFooter(
                items,
                metadata,
                WidgetGalleryMotionState(
                    boundedPosition,
                    currentIndex,
                    items.size,
                    rtl,
                    reduceMotion,
                    Modifier,
                    select,
                ),
                onOpen,
                supportingText = details,
            )
        }
    }
}

@Composable
private fun OrbitPrimaryArtwork(
    item: WidgetMedia,
    metadata: Boolean,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress = orbitProgress(item)
    val animatedProgress by animateFloatAsState(
        progress ?: 0f,
        animationSpec = Motion.tween(if (reduceMotion) 0 else Motion.DISCLOSURE),
        label = "orbitProgress",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Artwork(
            item,
            Modifier
                .fillMaxSize()
                .padding(7.dp)
                .clip(CircleShape)
                .clearAndSetSemantics { },
        )
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            val diameter = size.minDimension - inset * 2
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(palette.border.copy(alpha = .55f), diameter / 2, center, style = Stroke(stroke))
            if (progress != null && animatedProgress > 0f) {
                drawArc(
                    accent,
                    -90f,
                    animatedProgress * 360f,
                    false,
                    Offset(inset, inset),
                    Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (animatedProgress < 1f) {
                    val radians = (animatedProgress * 360f - 90f) * PI / 180
                    val point =
                        Offset(
                            center.x + cos(radians).toFloat() * diameter / 2,
                            center.y + sin(radians).toFloat() * diameter / 2,
                        )
                    drawCircle(palette.background, 5.dp.toPx(), point)
                    drawCircle(accent, 3.5.dp.toPx(), point)
                }
            }
        }
        if (metadata && progress != null && progress > 0f) {
            Text(
                if (progress == 1f) "已看完" else "已看 ${(progress * 100).toInt()}%",
                Modifier
                    .align(
                        Alignment.BottomCenter,
                    ).padding(
                        bottom = 22.dp,
                    ).background(
                        palette.background.copy(alpha = .86f),
                        CircleShape,
                    ).padding(horizontal = 10.dp, vertical = 4.dp),
                color = palette.text,
                style = AppTypography.caption.medium,
                maxLines = 1,
            )
        }
    }
}
