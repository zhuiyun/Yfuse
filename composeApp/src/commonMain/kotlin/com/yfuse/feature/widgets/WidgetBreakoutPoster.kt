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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetCutoutArtwork
import com.yfuse.core.widget.WidgetCutoutPhase
import com.yfuse.core.widget.WidgetCutoutStatus
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.logWidgetCutout
import com.yfuse.core.widget.rememberWidgetCutoutState
import com.yfuse.core.widget.widgetCutoutFailureAttributes
import kotlin.math.abs
import com.yfuse.core.designsystem.ThemeText as Text

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
        val artworkHeight = cardWidth * BREAKOUT_WINDOW_HEIGHT
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
    val state = rememberWidgetCutoutState(source, enabled = processForeground)
    val cutout = state.artwork
    var failed by remember(cutout) { mutableStateOf(false) }
    var loaded by remember(cutout) { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    if (settings) WidgetCutoutSettingsDialog { settings = false }
    Box(Modifier.fillMaxSize()) {
        if (cutout == null || failed) {
            val modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter,
                    ).fillMaxWidth()
                    .fillMaxHeight(.78f)
                    .clip(RoundedCornerShape(18.dp))
            if (source?.startsWith("http") == true) {
                FallbackImage(listOf(source), item.title, modifier, fitNarrow = true)
            } else {
                Artwork(item, modifier, poster = true)
            }
        } else {
            BreakoutLayers(cutout, source, onError = { failed = true }, onLoaded = { loaded = true })
        }
        val status =
            when {
                failed -> WidgetCutoutStatus(WidgetCutoutPhase.Failed, "抠图图片显示失败，点按重试")
                cutout != null && !loaded -> WidgetCutoutStatus(WidgetCutoutPhase.Downloading, "正在显示抠图")
                else -> state.status
            }
        LaunchedEffect(processForeground, cutout, failed, loaded, status.phase) {
            if (processForeground) {
                logWidgetCutout(
                    "card_render_state",
                    source,
                    mapOf(
                        "phase" to status.phase.name,
                        "hasArtwork" to (cutout != null).toString(),
                        "layersLoaded" to loaded.toString(),
                        "fallback" to (cutout == null || failed).toString(),
                        "imageError" to failed.toString(),
                    ),
                )
            }
        }
        if (processForeground && status.phase != WidgetCutoutPhase.Idle) {
            val configure =
                status.phase in
                    setOf(
                        WidgetCutoutPhase.NeedsKey,
                        WidgetCutoutPhase.Disabled,
                        WidgetCutoutPhase.InvalidKey,
                        WidgetCutoutPhase.NoCredits,
                    )
            val retry =
                status.phase in setOf(WidgetCutoutPhase.Failed, WidgetCutoutPhase.Rejected, WidgetCutoutPhase.Limited)
            Text(
                status.message,
                color = LocalPalette.current.text,
                style = AppTypography.caption.regular,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalPalette.current.background.copy(alpha = .94f))
                        .pressable(enabled = configure || retry, onClick = {
                            if (configure) {
                                settings = true
                            } else if (retry) {
                                failed = false
                                loaded = false
                                state.retry?.invoke()
                            }
                        })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun BreakoutLayers(
    artwork: WidgetCutoutArtwork,
    source: String?,
    onError: () -> Unit,
    onLoaded: () -> Unit,
) {
    var backgroundLoaded by remember(artwork) { mutableStateOf(false) }
    var foregroundLoaded by remember(artwork) { mutableStateOf(false) }
    LaunchedEffect(backgroundLoaded, foregroundLoaded) {
        if (backgroundLoaded && foregroundLoaded) {
            logWidgetCutout("renderer_ready", source)
            onLoaded()
        }
    }
    val framing =
        remember(artwork) {
            breakoutFraming(artwork.sourceWidth, artwork.sourceHeight, artwork.subjectTop, artwork.subjectBottom)
        }
    val shape = remember(framing.frameTopFraction) { BreakoutFrameShape(framing.frameTopFraction) }
    LaunchedEffect(artwork) {
        logWidgetCutout(
            "renderer_started",
            source,
            mapOf(
                "width" to artwork.sourceWidth.toString(),
                "height" to artwork.sourceHeight.toString(),
                "subjectTop" to artwork.subjectTop.toString(),
                "subjectBottom" to artwork.subjectBottom.toString(),
                "verticalBias" to framing.verticalBias.toString(),
                "frameTopFraction" to framing.frameTopFraction.toString(),
            ),
        )
    }
    val alignment = BiasAlignment(0f, framing.verticalBias)
    val surface = LocalPalette.current.card
    AsyncImage(
        model = artwork.backgroundModel,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().clip(shape).background(surface),
        alignment = alignment,
        contentScale = ContentScale.Crop,
        onError = {
            logWidgetCutout(
                "image_layer_failed",
                source,
                widgetCutoutFailureAttributes(it.result.throwable) + ("layer" to "background"),
                warning = true,
            )
            onError()
        },
        onSuccess = {
            logWidgetCutout("image_layer_ready", source, mapOf("layer" to "background"))
            backgroundLoaded = true
        },
    )
    AsyncImage(
        model = artwork.foregroundModel,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
        alignment = alignment,
        contentScale = ContentScale.Crop,
        onError = {
            logWidgetCutout(
                "image_layer_failed",
                source,
                widgetCutoutFailureAttributes(it.result.throwable) + ("layer" to "foreground"),
                warning = true,
            )
            onError()
        },
        onSuccess = {
            logWidgetCutout("image_layer_ready", source, mapOf("layer" to "foreground"))
            foregroundLoaded = true
        },
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
