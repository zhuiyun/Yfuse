package com.yfuse.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core2.android.AndroidAssSubtitleRenderer
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.core2.subtitle.YSubtitleClockAnchor
import com.yfuse.core2.subtitle.YSubtitleCue
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
    subtitleAppearance: SubtitleAppearance,
    modifier: Modifier = Modifier,
    /** 氛围光 reads this surface; the view is the picture, so no source rect is needed. */
    ambientSampler: AmbientFrameSampler? = null,
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
                    ambientSampler?.attach(this)
                }
            },
            update = { view ->
                view.setProtectedContent(protectedContent)
                view.bind(engine.player)
                ambientSampler?.attach(view)
            },
            onRelease = { view ->
                ambientSampler?.detach(view)
                view.unbind()
            },
        )
        Core2SubtitleOverlay(
            engine = engine,
            canvasSize = IntSize(videoWidth, videoHeight),
            offsetMs = subtitleOffsetMs,
            scale = subtitleScale,
            brightness = subtitleBrightness,
            position = subtitlePosition,
            appearance = subtitleAppearance,
            modifier = surfaceModifier,
        )
        DiscNavigationOverlay(engine = engine, layoutSize = layoutSize)
    }
}

@Composable
private fun Core2SubtitleOverlay(
    engine: YPlayerVideoEngineAdapter,
    canvasSize: IntSize,
    offsetMs: Long,
    scale: Float,
    brightness: Float,
    position: Float,
    appearance: SubtitleAppearance,
    modifier: Modifier,
) {
    val playerState by engine.player.state.collectAsState()

    fun hasActiveAss(
        cues: List<YSubtitleCue>,
        delayMs: Long,
    ): Boolean {
        val timeUs = (playerState.positionMs - delayMs) * MICROS_PER_MILLISECOND
        return cues.any { it.payload is YSubtitlePayload.AssEvent && timeUs >= it.startUs && timeUs < it.endUs }
    }
    val hasAss =
        hasActiveAss(playerState.subtitleCues, offsetMs) ||
            hasActiveAss(playerState.secondarySubtitleCues, playerState.secondarySubtitleOffsetMs)

    // Android Lint resolves this commonMain return type as Unit; the cached anchor is immutable.
    @SuppressLint("RememberReturnType")
    val clock: YSubtitleClockAnchor =
        remember<YSubtitleClockAnchor>(
            playerState.positionMs,
            playerState.playing,
            playerState.buffering,
            playerState.speed,
        ) {
            YSubtitleClockAnchor(
                playerState.positionMs,
                System.nanoTime(),
                playerState.playing && !playerState.buffering,
                playerState.speed,
            )
        }
    var frameClock by remember { mutableStateOf(clock to clock.positionMs) }
    LaunchedEffect(clock, hasAss) {
        if (!hasAss || !clock.advancing) {
            frameClock = clock to clock.positionMs
            return@LaunchedEffect
        }
        while (true) withFrameNanos { frameClock = clock to clock.positionAt(it) }
    }
    val subtitlePositionMs = if (hasAss && frameClock.first == clock) frameClock.second else playerState.positionMs

    BoxWithConstraints(modifier) {
        val viewport = DpSize(maxWidth, maxHeight)
        val dual = playerState.secondarySubtitleTrackId != null
        val channel: @Composable (Boolean) -> Unit = { secondary ->
            Core2SubtitleChannel(
                cues = if (secondary) playerState.secondarySubtitleCues else playerState.subtitleCues,
                positionMs = subtitlePositionMs,
                timelineGeneration = playerState.diagnostics.outputEvidenceGeneration,
                canvasSize = canvasSize,
                offsetMs = if (secondary) playerState.secondarySubtitleOffsetMs else offsetMs,
                scale = scale,
                brightness = brightness,
                position = position,
                appearance = appearance,
                secondary = secondary,
                dual = dual,
                viewport = viewport,
                modifier = if (dual) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            )
        }
        if (dual) {
            // Measure both tracks in one stack: multiline text and bitmap display sets reserve
            // their real height before the other channel is placed. The secondary stays lowest.
            Column(
                modifier =
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(bottom = maxHeight * (1f - position.coerceIn(0.60f, 0.96f))),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                channel(false)
                channel(true)
            }
        } else {
            channel(false)
        }
    }
}

@Composable
private fun Core2SubtitleChannel(
    cues: List<YSubtitleCue>,
    positionMs: Long,
    timelineGeneration: Long,
    canvasSize: IntSize,
    offsetMs: Long,
    scale: Float,
    brightness: Float,
    position: Float,
    appearance: SubtitleAppearance,
    secondary: Boolean,
    dual: Boolean,
    viewport: DpSize,
    modifier: Modifier,
) {
    val timeline: YSubtitleTimeline =
        remember(cues) {
            val index: YSubtitleTimeline = YSubtitleTimeline(cues)
            index
        }
    val activeCues =
        remember<List<YSubtitleCue>>(timeline, positionMs, offsetMs) {
            timeline.activeAt(positionMs * MICROS_PER_MILLISECOND, offsetMs * MICROS_PER_MILLISECOND)
        }
    val assRenderer = remember { AndroidAssSubtitleRenderer() }
    DisposableEffect(assRenderer) { onDispose(assRenderer::close) }
    val assOverrides = remember(appearance) { appearance.assStyleOverrides() }
    LaunchedEffect(assRenderer, cues, positionMs, timelineGeneration, offsetMs, canvasSize, assOverrides) {
        assRenderer.submit(
            cues,
            (positionMs - offsetMs) * MICROS_PER_MILLISECOND,
            canvasSize.width,
            canvasSize.height,
            assOverrides,
            timelineGeneration = timelineGeneration,
        )
    }
    val assBitmaps by assRenderer.bitmaps.collectAsState()
    if (activeCues.isEmpty()) return
    val activeText = activeCues.mapNotNull { cue -> cue.payload as? YSubtitlePayload.Text }
    val activeBitmaps =
        activeCues.mapNotNull { cue -> cue.payload as? YSubtitlePayload.BitmapArgb } +
            if (activeCues.any { it.payload is YSubtitlePayload.AssEvent }) assBitmaps else emptyList()

    val bitmapScale = scale.coerceIn(0.6f, 1.8f)
    val bitmapBounds = core2SubtitleBitmapBounds(activeBitmaps, bitmapScale)
    val bitmapContent: @Composable () -> Unit = {
        activeBitmaps.forEach { payload ->
            val bitmap = remember(payload) {
                Bitmap.createBitmap(payload.pixels, payload.width, payload.height, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }
            val scaledWidth = payload.width * bitmapScale
            val scaledHeight = payload.height * bitmapScale
            val x = payload.x - (scaledWidth - payload.width) / 2f
            val authoredY = (payload.y - (scaledHeight - payload.height) / 2f) / payload.canvasHeight
            // Translate the entire scaled display set together; ASS/PGS rectangles keep their
            // relative positions, including outlines and independently rendered glyphs.
            val y =
                if (dual) authoredY - bitmapBounds.first else
                    authoredY + position.coerceIn(0.60f, 0.96f) - DEFAULT_SUBTITLE_POSITION
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.offset(
                    x = viewport.width * (x / payload.canvasWidth),
                    y = viewport.height * y,
                ).requiredSize(
                    width = viewport.width * (scaledWidth / payload.canvasWidth),
                    height = viewport.height * (scaledHeight / payload.canvasHeight),
                ).background(Color(appearance.backgroundColorArgb.toULong()))
                    .graphicsLayer(alpha = brightness.coerceIn(0.35f, 1f)),
            )
        }
    }
    if (dual) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (activeBitmaps.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(viewport.height * (bitmapBounds.second - bitmapBounds.first))) {
                    bitmapContent()
                }
            }
            if (activeText.isNotEmpty()) {
                Core2SubtitleText(
                    activeText,
                    core2SubtitleAlignment(authored = 2, secondary = secondary, dual = dual),
                    scale,
                    brightness,
                    appearance,
                    Modifier.fillMaxWidth(0.92f).padding(horizontal = 12.dp),
                )
            }
        }
    } else {
        Box(modifier) {
            bitmapContent()
            activeText.groupBy { core2SubtitleAlignment(it.style.alignment, secondary, dual) }
                .forEach { (alignmentCode, payloads) ->
                    Core2SubtitleText(
                        payloads, alignmentCode, scale, brightness, appearance,
                        Modifier.align(alignmentCode.toComposeAlignment()).fillMaxWidth(0.92f)
                            .padding(horizontal = 12.dp).then(
                                when {
                                    alignmentCode <= 3 -> Modifier.padding(
                                        bottom = viewport.height * (1f - position.coerceIn(0.60f, 0.96f)),
                                    )
                                    alignmentCode >= 7 -> Modifier.padding(top = viewport.height * 0.05f)
                                    else -> Modifier
                                },
                            ),
                    )
                }
        }
    }
}

/** Normalized bounds after scaling, shared by measurement and placement. */
internal fun core2SubtitleBitmapBounds(
    bitmaps: List<YSubtitlePayload.BitmapArgb>,
    scale: Float,
): Pair<Float, Float> {
    if (bitmaps.isEmpty()) return 0f to 0f
    val normalizedScale = scale.coerceIn(0.6f, 1.8f)
    val top = bitmaps.minOf { (it.y - it.height * (normalizedScale - 1f) / 2f) / it.canvasHeight }
    val bottom = bitmaps.maxOf { (it.y + it.height * (normalizedScale + 1f) / 2f) / it.canvasHeight }
    return top to bottom
}

@Composable
private fun Core2SubtitleText(
    payloads: List<YSubtitlePayload.Text>,
    alignmentCode: Int,
    scale: Float,
    brightness: Float,
    appearance: SubtitleAppearance,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        payloads.forEach { payload ->
            val cueStyle = payload.style
            val authoredColor = cueStyle.primaryColorArgb?.let(::Color) ?: Color.White
            val useAuthoredColor = appearance == SubtitleAppearance()
            val requestedColor = Color(appearance.textColorArgb.toULong())
            val backgroundColor = Color(appearance.backgroundColorArgb.toULong())
            val outlineColor = Color(appearance.outlineColorArgb.toULong())
            val textScale = scale.coerceIn(0.6f, 1.8f)
            val authoredSize = cueStyle.fontSizePoints?.coerceIn(10f, 64f) ?: 22f
            Text(
                text = payload.plainText,
                color =
                    (if (useAuthoredColor) authoredColor else requestedColor).copy(
                        alpha =
                            (if (useAuthoredColor) authoredColor.alpha else requestedColor.alpha) *
                                brightness.coerceIn(0.35f, 1f),
                    ),
                fontSize = (authoredSize * textScale).sp,
                lineHeight = (authoredSize * 1.23f * textScale).sp,
                textAlign = alignmentCode.toTextAlign(),
                fontWeight = if (cueStyle.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (cueStyle.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (cueStyle.underline) TextDecoration.Underline else TextDecoration.None,
                style =
                    androidx.compose.ui.text.TextStyle(
                        background = backgroundColor,
                        shadow =
                            Shadow(
                                color = outlineColor,
                                offset = Offset(0f, cueStyle.shadow ?: 2f),
                                blurRadius =
                                    if (appearance.outlineWidth <= 0f) {
                                        0f
                                    } else {
                                        maxOf(
                                            2f,
                                            maxOf(cueStyle.outline ?: 0f, appearance.outlineWidth) * 2.5f,
                                        )
                                    },
                            ),
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun core2SubtitleAlignment(
    authored: Int,
    secondary: Boolean,
    dual: Boolean,
): Int =
    when {
        secondary -> 2
        dual -> 2
        else -> authored
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

/** Preserve authored styles by default; explicit user styling reaches the native ASS track too. */
internal fun SubtitleAppearance.assStyleOverrides(): List<String> {
    if (this == SubtitleAppearance()) return emptyList()

    fun assColor(argb: Long): String {
        val alpha = 255L - ((argb ushr 24) and 255L)
        val red = (argb ushr 16) and 255L
        val green = (argb ushr 8) and 255L
        val blue = argb and 255L
        return "&H" + ((alpha shl 24) or (blue shl 16) or (green shl 8) or red).toString(16).padStart(8, '0')
    }
    return listOf(
        "PrimaryColour=" + assColor(textColorArgb),
        "OutlineColour=" + assColor(outlineColorArgb),
        "BackColour=" + assColor(backgroundColorArgb),
        "BorderStyle=1",
        "Outline=" + outlineWidth.coerceIn(0f, 10f),
        "Shadow=0",
    )
}
