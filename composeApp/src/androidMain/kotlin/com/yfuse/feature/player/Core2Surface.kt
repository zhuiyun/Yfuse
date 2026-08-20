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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
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
                    bind(engine.player)
                }
            },
            update = { view ->
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
    val activeText =
        activeCues.mapNotNull { cue -> (cue.payload as? YSubtitlePayload.Text)?.plainText }.joinToString("\n")
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
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier =
                    Modifier
                        .offset(
                            x = (maxWidth.value * payload.x / payload.canvasWidth).dp,
                            y = (maxHeight.value * payload.y / payload.canvasHeight).dp,
                        ).requiredSize(
                            width = (maxWidth.value * payload.width / payload.canvasWidth).dp,
                            height = (maxHeight.value * payload.height / payload.canvasHeight).dp,
                        ),
            )
        }
        if (activeText.isNotEmpty()) {
            Text(
                text = activeText,
                color = Color.White.copy(alpha = brightness.coerceIn(0.35f, 1f)),
                fontSize = (22f * scale.coerceIn(0.6f, 1.8f)).sp,
                lineHeight = (27f * scale.coerceIn(0.6f, 1.8f)).sp,
                textAlign = TextAlign.Center,
                style =
                    androidx.compose.ui.text.TextStyle(
                        shadow =
                            Shadow(
                                color = Color.Black,
                                offset = Offset(0f, 2f),
                                blurRadius = 5f,
                            ),
                    ),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.92f)
                        .padding(horizontal = 12.dp)
                        .padding(bottom = (maxHeight.value * (1f - position.coerceIn(0.60f, 0.96f))).dp),
            )
        }
    }
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

    init {
        holder.addCallback(this)
    }

    fun bind(next: YPlayer) {
        if (player === next) return
        player?.setVideoOutput(null)
        player = next
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
        player?.setVideoOutput(AndroidSurfaceVideoOutput(surface))
    }
}

private const val MICROS_PER_MILLISECOND = 1_000L
