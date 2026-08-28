package com.yfuse.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YSubtitleTimeline
import kotlin.math.roundToInt

/**
 * SurfaceView host for YCore 2.0.
 *
 * The Compose layer never receives a decoded frame. Surface creation/destruction is translated into
 * the opaque YPlayer output contract and MediaCodec stays connected directly to the platform
 * Surface/OEM HDR pipeline.
 */
@Composable
internal fun Core2Surface(
    engine: YPlayerVideoEngineAdapter,
    protectedContent: Boolean,
    scaleMode: VideoScaleMode,
    videoWidth: Int,
    videoHeight: Int,
    subtitleOffsetMs: Long,
    subtitleScale: Float,
    subtitleBrightness: Float,
    subtitlePosition: Float,
    modifier: Modifier = Modifier,
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    val surfaceSize =
        core2SurfaceSize(
            container = layoutSize,
            video = IntSize(videoWidth, videoHeight),
            scaleMode = scaleMode,
        )
    val density = LocalDensity.current
    val surfaceModifier =
        if (surfaceSize == IntSize.Zero) {
            Modifier.fillMaxSize()
        } else {
            with(density) {
                Modifier.requiredSize(surfaceSize.width.toDp(), surfaceSize.height.toDp())
            }
        }
    Box(
        modifier = modifier.clipToBounds().onSizeChanged { layoutSize = it },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = surfaceModifier,
            factory = { context ->
                Core2SurfaceView(context).apply {
                    setProtectedContent(protectedContent)
                    bind(engine.player)
                }
            },
            update = { view ->
                view.setProtectedContent(protectedContent)
                view.bind(engine.player)
            },
            onRelease = Core2SurfaceView::unbind,
        )
        Core2SubtitleOverlay(
            engine = engine,
            offsetMs = subtitleOffsetMs,
            scale = subtitleScale,
            brightness = subtitleBrightness,
            position = subtitlePosition,
            modifier = surfaceModifier,
        )
        DiscNavigationOverlay(engine = engine, layoutSize = layoutSize)
    }
}

@Composable
private fun Core2SubtitleOverlay(
    engine: YPlayerVideoEngineAdapter,
    offsetMs: Long,
    scale: Float,
    brightness: Float,
    position: Float,
    modifier: Modifier,
) {
    val playerState by engine.player.state.collectAsState()
    val activeCues =
        remember(playerState.subtitleCues, playerState.positionMs, offsetMs) {
            YSubtitleTimeline(playerState.subtitleCues)
                .activeAt(
                    playbackPositionUs = playerState.positionMs * MICROS_PER_MILLISECOND,
                    delayUs = offsetMs * MICROS_PER_MILLISECOND,
                )
        }
    if (activeCues.isEmpty()) return
    val activeText = activeCues.mapNotNull { cue -> cue.payload as? YSubtitlePayload.Text }
    val activeBitmaps = activeCues.mapNotNull { cue -> cue.payload as? YSubtitlePayload.BitmapArgb }

    BoxWithConstraints(modifier) {
        activeBitmaps.forEach { payload ->
            val bitmap =
                remember(payload) {
                    Bitmap
                        .createBitmap(
                            payload.pixels,
                            payload.width,
                            payload.height,
                            Bitmap.Config.ARGB_8888,
                        ).asImageBitmap()
                }
            val bitmapScale = scale.coerceIn(0.6f, 1.8f)
            val scaledWidth = payload.width * bitmapScale
            val scaledHeight = payload.height * bitmapScale
            val x = payload.x - (scaledWidth - payload.width) / 2f
            val authoredY = payload.y - (scaledHeight - payload.height) / 2f
            val positionDelta =
                payload.canvasHeight *
                    (position.coerceIn(0.60f, 0.96f) - DEFAULT_SUBTITLE_POSITION)
            val y = authoredY + positionDelta
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier =
                    Modifier
                        .offset(
                            x = (maxWidth.value * x / payload.canvasWidth).dp,
                            y = (maxHeight.value * y / payload.canvasHeight).dp,
                        ).requiredSize(
                            width = (maxWidth.value * scaledWidth / payload.canvasWidth).dp,
                            height = (maxHeight.value * scaledHeight / payload.canvasHeight).dp,
                        ).graphicsLayer(alpha = brightness.coerceIn(0.35f, 1f)),
            )
        }
        activeText.forEach { payload ->
            val cueStyle = payload.style
            val authoredColor = cueStyle.primaryColorArgb?.let(::Color) ?: Color.White
            val textScale = scale.coerceIn(0.6f, 1.8f)
            val authoredSize = cueStyle.fontSizePoints?.coerceIn(10f, 64f) ?: 22f
            val alignment = cueStyle.alignment.toComposeAlignment()
            Text(
                text = payload.plainText,
                color =
                    authoredColor.copy(
                        alpha = authoredColor.alpha * brightness.coerceIn(0.35f, 1f),
                    ),
                fontSize = (authoredSize * textScale).sp,
                lineHeight = (authoredSize * 1.23f * textScale).sp,
                textAlign = cueStyle.alignment.toTextAlign(),
                fontWeight = if (cueStyle.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (cueStyle.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (cueStyle.underline) TextDecoration.Underline else TextDecoration.None,
                style =
                    androidx.compose.ui.text.TextStyle(
                        shadow =
                            Shadow(
                                color = Color.Black,
                                offset = Offset(0f, cueStyle.shadow ?: 2f),
                                blurRadius = maxOf(2f, (cueStyle.outline ?: 2f) * 2.5f),
                            ),
                    ),
                modifier =
                    Modifier
                        .align(alignment)
                        .fillMaxWidth(0.92f)
                        .padding(horizontal = 12.dp)
                        .then(
                            if (cueStyle.alignment <= 3) {
                                Modifier.padding(
                                    bottom =
                                        (maxHeight.value * (1f - position.coerceIn(0.60f, 0.96f))).dp,
                                )
                            } else if (cueStyle.alignment >= 7) {
                                Modifier.padding(top = (maxHeight.value * 0.05f).dp)
                            } else {
                                Modifier
                            },
                        ),
            )
        }
    }
}

private fun Int.toComposeAlignment(): Alignment =
    when (this) {
        1 -> Alignment.BottomStart
        2 -> Alignment.BottomCenter
        3 -> Alignment.BottomEnd
        4 -> Alignment.CenterStart
        5 -> Alignment.Center
        6 -> Alignment.CenterEnd
        7 -> Alignment.TopStart
        8 -> Alignment.TopCenter
        9 -> Alignment.TopEnd
        else -> Alignment.BottomCenter
    }

private fun Int.toTextAlign(): TextAlign =
    when (this) {
        1, 4, 7 -> TextAlign.Start
        3, 6, 9 -> TextAlign.End
        else -> TextAlign.Center
    }

internal fun core2SurfaceSize(
    container: IntSize,
    video: IntSize,
    scaleMode: VideoScaleMode,
): IntSize {
    if (container.width <= 0 || container.height <= 0) return IntSize.Zero
    if (video.width <= 0 || video.height <= 0 || scaleMode == VideoScaleMode.Stretch) return container

    val widthScale = container.width.toDouble() / video.width.toDouble()
    val heightScale = container.height.toDouble() / video.height.toDouble()
    val scale =
        when (scaleMode) {
            VideoScaleMode.Fit -> minOf(widthScale, heightScale)
            VideoScaleMode.Fill -> maxOf(widthScale, heightScale)
            VideoScaleMode.Stretch -> return container
        }
    return IntSize(
        width = (video.width * scale).roundToInt().coerceAtLeast(1),
        height = (video.height * scale).roundToInt().coerceAtLeast(1),
    )
}

private class Core2SurfaceView(
    context: Context,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private var player: YPlayer? = null
    private var protectedContent = false

    init {
        holder.addCallback(this)
    }

    fun bind(next: YPlayer) {
        if (player === next) return
        player?.setVideoOutput(null)
        player = next
        attachCurrentSurface()
    }

    fun setProtectedContent(required: Boolean) {
        if (protectedContent == required) return
        player?.setVideoOutput(null)
        protectedContent = required
        setSecure(required)
        attachCurrentSurface()
    }

    fun unbind() {
        player?.setVideoOutput(null)
        player = null
        holder.removeCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachCurrentSurface()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        attachCurrentSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.setVideoOutput(null)
    }

    private fun attachCurrentSurface() {
        val surface = holder.surface
        if (!surface.isValid) return
        player?.setVideoOutput(
            AndroidSurfaceVideoOutput(
                surface = surface,
                protectedContent = protectedContent,
            ),
        )
    }
}

private const val MICROS_PER_MILLISECOND = 1_000L
