package com.yfuse.feature.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.widget.WidgetMedia

/** A joined strip of films, clipped by two continuous curves rather than separate cards. */
@Composable
internal fun WidgetRibbonGallery(
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val accent = LocalAccentColors.current.accent
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val stageWidth = widgetGalleryWidth(maxWidth, compact, LocalWidgetGalleryMaxHeight.current, .76f)
        val stageHeight = stageWidth * .76f
        val motion = rememberWidgetGalleryMotion("ribbon-gallery", items, with(density) { (stageWidth * .44f).toPx() })
        val phase = if (motion.reduceMotion) 0f else motion.position
        val strip =
            remember(motion.position, motion.rtl, items.size, phase) {
                RibbonGalleryShape(motion.position, items.size, motion.rtl, phase)
            }
        val currentMotion by rememberUpdatedState(motion)
        val currentPhase by rememberUpdatedState(phase)
        val currentItems by rememberUpdatedState(items)
        val open by rememberUpdatedState(onOpen)
        Column(
            Modifier.width(widgetGalleryViewportWidth(maxWidth, stageWidth)),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            WidgetGalleryStage(stageWidth, stageHeight, items, motion.currentIndex, motion.select) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(stageHeight)
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTapGestures { point ->
                                val x = point.x / size.width.coerceAtLeast(1)
                                val y = point.y / size.height.coerceAtLeast(1)
                                val range =
                                    ribbonGalleryExtent(currentMotion.position, currentItems.size, currentMotion.rtl)
                                val wave = ribbonGalleryWave(x, currentPhase)
                                if (x in range && y in (0.12f + wave)..(0.82f + wave)) {
                                    val index =
                                        ribbonGalleryIndexAt(
                                            x,
                                            currentMotion.position,
                                            currentItems.size,
                                            currentMotion.rtl,
                                        )
                                    val item = currentItems.getOrNull(index)
                                    if (item != null) {
                                        if (index ==
                                            currentMotion.currentIndex
                                        ) {
                                            open(item)
                                        } else {
                                            currentMotion.select(index)
                                        }
                                    }
                                }
                            }
                        }.onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp &&
                                (event.key == Key.Enter || event.key == Key.DirectionCenter)
                            ) {
                                currentItems.getOrNull(currentMotion.currentIndex)?.let(open)
                                true
                            } else {
                                false
                            }
                        }.semantics {
                            role = Role.Button
                            contentDescription = items[motion.currentIndex].title
                            onClick("查看详情") {
                                onOpen(items[motion.currentIndex])
                                true
                            }
                        }.then(motion.inputModifier),
                ) {
                    Box(
                        Modifier.fillMaxSize().clip(strip).clearAndSetSemantics { },
                        // Artwork positions below already mirror in physical coordinates.
                        contentAlignment = AbsoluteAlignment.TopLeft,
                    ) {
                        val visible = (motion.currentIndex - 2..motion.currentIndex + 2).filter { it in items.indices }
                        visible.forEach { index ->
                            val center = .5f + (index - motion.position) * .44f * if (motion.rtl) -1 else 1
                            val width = if (items.size == 1) .84f else .44f
                            Artwork(
                                items[index],
                                Modifier
                                    .absoluteOffset(x = stageWidth * (center - width / 2), y = stageHeight * .055f)
                                    .width(stageWidth * width + .5.dp)
                                    .height(stageHeight * .83f),
                                poster = true,
                            )
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            // Thin joins belong to one strip, and move with its photographs.
                            visible.dropLast(1).forEach { index ->
                                val x = .5f + (index + .5f - motion.position) * .44f * if (motion.rtl) -1 else 1
                                drawLine(
                                    Color.White.copy(alpha = .7f),
                                    Offset(x * size.width, 0f),
                                    Offset(x * size.width, size.height),
                                    1.dp.toPx(),
                                )
                            }
                            drawPath(
                                strip.path(size.width, size.height),
                                Color.White.copy(alpha = .24f),
                                style = Stroke(1.dp.toPx()),
                            )
                        }
                    }
                    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                        val centerY = (.82f + ribbonGalleryWave(.5f, phase)) * size.height
                        // A fixed centre marker identifies the film named in the footer.
                        drawLine(
                            accent.copy(alpha = .5f),
                            Offset(size.width / 2, centerY + 5.dp.toPx()),
                            Offset(
                                size.width / 2,
                                centerY + 13.dp.toPx(),
                            ),
                            1.5.dp.toPx(),
                        )
                        drawCircle(accent, 2.5.dp.toPx(), Offset(size.width / 2, centerY + 17.dp.toPx()))
                    }
                }
            }
            WidgetGalleryFooter(items, metadata, motion, onOpen)
        }
    }
}

private fun ribbonGalleryExtent(
    position: Float,
    count: Int,
    rtl: Boolean,
): ClosedFloatingPointRange<Float> {
    if (count == 1) return 0.08f..0.92f
    val start = (.28f - position * .44f).coerceIn(0f, 1f)
    val end = (.72f + (count - 1 - position) * .44f).coerceIn(0f, 1f)
    return if (rtl) (1f - end)..(1f - start) else start..end
}

private class RibbonGalleryShape(
    private val position: Float,
    private val count: Int,
    private val rtl: Boolean,
    private val phase: Float,
) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): Outline = Outline.Generic(path(size.width, size.height))

    fun path(
        width: Float,
        height: Float,
    ): Path {
        val extent = ribbonGalleryExtent(position, count, rtl)
        return Path().apply {
            repeat(65) { step ->
                val x = extent.start + (extent.endInclusive - extent.start) * step / 64f
                val y = (.12f + ribbonGalleryWave(x, phase)) * height
                if (step == 0) moveTo(x * width, y) else lineTo(x * width, y)
            }
            for (step in 64 downTo 0) {
                val x = extent.start + (extent.endInclusive - extent.start) * step / 64f
                lineTo(x * width, (.82f + ribbonGalleryWave(x, phase)) * height)
            }
            close()
        }
    }
}
