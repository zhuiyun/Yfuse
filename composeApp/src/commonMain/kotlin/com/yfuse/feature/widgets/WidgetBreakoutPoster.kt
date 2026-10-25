package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetCutoutArtwork
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.rememberWidgetCutoutArtwork
import kotlin.math.abs

@Composable
internal fun WidgetBreakoutPoster(
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val stageWidth = maxWidth.coerceAtMost(if (compact) 340.dp else 480.dp)
        val cardWidth = stageWidth * .82f
        val artworkHeight = cardWidth * .82f
        val density = LocalDensity.current
        val motion = rememberWidgetGalleryMotion("breakout-poster", items, with(density) { (stageWidth * .72f).toPx() })
        Column(Modifier.width(stageWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(artworkHeight + 24.dp)
                    .clipToBounds()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Enter || event.key == Key.DirectionCenter)
                        ) {
                            onOpen(items[motion.currentIndex])
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
                contentAlignment = Alignment.Center,
            ) {
                val indices = (motion.currentIndex - 2..motion.currentIndex + 2).filter { it in items.indices }
                indices.sortedByDescending { abs(it - motion.position) }.forEach { index ->
                    key(items[index].id, index) {
                        val relative = index - motion.position
                        val distance = abs(relative).coerceAtMost(1f)
                        val selected = index == motion.currentIndex
                        Box(
                            Modifier
                                .size(cardWidth, artworkHeight)
                                .zIndex(2f - abs(relative))
                                .graphicsLayer {
                                    translationX = relative * stageWidth.toPx() * .75f * if (motion.rtl) -1f else 1f
                                    translationY = distance * 12.dp.toPx()
                                    scaleX = 1f - distance * .12f
                                    scaleY = scaleX
                                    rotationY = if (motion.reduceMotion) 0f else relative.coerceIn(-1f, 1f) * -8f
                                    cameraDistance = 16 * density.density
                                    alpha = 1f - distance * .38f
                                    transformOrigin = TransformOrigin(.5f, .8f)
                                }.pressable(
                                    label = items[index].title,
                                    onClickLabel = if (selected) "查看详情" else "选中影片",
                                    pressedScale = 1f,
                                    lightFeedback = false,
                                    onClick = { if (selected) onOpen(items[index]) else motion.select(index) },
                                ),
                        ) {
                            BreakoutArtwork(items[index], selected)
                        }
                    }
                }
            }
            WidgetGalleryFooter(items, metadata, motion, onOpen)
        }
    }
}

@Composable
private fun BreakoutArtwork(
    item: WidgetMedia,
    processForeground: Boolean,
) {
    val original = item.poster.ifBlank { item.backdrop }.takeIf(String::isNotBlank)
    val isBundledExample = item.id.startsWith("preview-") || item.id.startsWith("demo-")
    val previewIndex = item.id.substringAfterLast('-').toIntOrNull() ?: 0
    val source =
        original ?: if (isBundledExample && previewIndex % 3 == 1) {
            "file:///android_asset/widget-preview/breakout-astronaut.png"
        } else {
            null
        }
    val cutout = rememberWidgetCutoutArtwork(source, enabled = processForeground)
    var failed by remember(cutout) { mutableStateOf(false) }
    if (cutout == null || failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Artwork(item, Modifier.fillMaxWidth().fillMaxHeight(.78f).clip(RoundedCornerShape(18.dp)), poster = true)
        }
    } else {
        BreakoutLayers(cutout, onError = { failed = true })
    }
}

@Composable
private fun BreakoutLayers(
    artwork: WidgetCutoutArtwork,
    onError: () -> Unit,
) {
    val framing =
        remember(artwork) {
            breakoutFraming(artwork.sourceWidth, artwork.sourceHeight, artwork.subjectTop, artwork.subjectBottom)
        }
    val shape = remember(framing.frameTopFraction) { BreakoutFrameShape(framing.frameTopFraction) }
    val alignment = BiasAlignment(0f, framing.verticalBias)
    val surface = LocalPalette.current.card
    AsyncImage(
        model = artwork.backgroundModel,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().clip(shape).background(surface),
        alignment = alignment,
        contentScale = ContentScale.Crop,
        onError = { onError() },
    )
    AsyncImage(
        model = artwork.foregroundModel,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
        alignment = alignment,
        contentScale = ContentScale.Crop,
        onError = { onError() },
    )
}

/** The background alone starts below the subject. The whole stage owns the outer clipping. */
private class BreakoutFrameShape(
    private val topFraction: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Rounded(
            RoundRect(
                left = 0f,
                top = size.height * topFraction,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(with(density) { 18.dp.toPx() }),
            ),
        )
}
