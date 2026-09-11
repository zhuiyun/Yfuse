package com.yfuse.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core2.android.AndroidAssSubtitleRenderer
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.core2.subtitle.YSubtitleClockAnchor
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YSubtitleTimeline
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

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
    secondarySubtitleScale: Float = subtitleScale,
    subtitleBrightness: Float,
    subtitlePosition: Float,
    subtitleAppearance: SubtitleAppearance,
    modifier: Modifier = Modifier,
    /**
     * False while the player is in 画中画 or otherwise off screen. The ASS renderer's per-frame
     * loop is the most expensive thing in this file; nothing should drive it for a picture the
     * viewer is not reading captions from.
     */
    visible: Boolean = true,
    /** 氛围光 reads this surface; the view is the picture, so no source rect is needed. */
    ambientSampler: AmbientFrameSampler? = null,
    /** Drawn above the surface and below the subtitle overlays, so light never covers a caption. */
    ambientLayer: @Composable () -> Unit = {},
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    val surfaceSize =
        core2SurfaceSize(
            container = layoutSize,
            video = IntSize(videoWidth, videoHeight),
            scaleMode = scaleMode,
        )
    val density = LocalDensity.current
    // 画面比例 used to resize the picture between two frames, which is the one moment the change
    // is hardest to read — the frame simply stands somewhere else. The first measurement stays a
    // cut: there is no previous rectangle for it to travel from.
    var measured by remember { mutableStateOf(false) }
    LaunchedEffect(surfaceSize) { if (surfaceSize != IntSize.Zero) measured = true }
    val pictureSpec = Motion.settle<Dp>(LocalAccessibilityOptions.current.reduceMotion || !measured)
    val pictureWidth by animateDpAsState(
        targetValue = with(density) { surfaceSize.width.toDp() },
        animationSpec = pictureSpec,
        label = "core2PictureWidth",
    )
    val pictureHeight by animateDpAsState(
        targetValue = with(density) { surfaceSize.height.toDp() },
        animationSpec = pictureSpec,
        label = "core2PictureHeight",
    )
    val surfaceModifier =
        if (surfaceSize == IntSize.Zero) {
            Modifier.fillMaxSize()
        } else {
            Modifier.requiredSize(pictureWidth, pictureHeight)
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
        ambientLayer()
        Core2SubtitleOverlay(
            engine = engine,
            canvasSize = IntSize(videoWidth, videoHeight),
            offsetMs = subtitleOffsetMs,
            scale = subtitleScale,
            secondaryScale = secondarySubtitleScale,
            brightness = subtitleBrightness,
            position = subtitlePosition,
            appearance = subtitleAppearance,
            visible = visible,
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
    secondaryScale: Float,
    brightness: Float,
    position: Float,
    appearance: SubtitleAppearance,
    visible: Boolean,
    modifier: Modifier,
) {
    // Narrow projections of the player state. Read whole, it put this overlay — and therefore
    // every caption on screen — on the position tick, and rebuilt it for diagnostics counters
    // no caption has ever looked at.
    val livePlayerState = engine.player.state.collectAsState()
    val clockSample by remember(livePlayerState) {
        derivedStateOf {
            val current = livePlayerState.value
            Core2SubtitleClockSample(
                positionMs = current.positionMs,
                advancing = current.playing && !current.buffering,
                speed = current.speed,
            )
        }
    }
    val primaryCues by remember(livePlayerState) { derivedStateOf { livePlayerState.value.subtitleCues } }
    val secondaryCues by remember(livePlayerState) { derivedStateOf { livePlayerState.value.secondarySubtitleCues } }
    val secondaryOffsetMs by remember(livePlayerState) {
        derivedStateOf { livePlayerState.value.secondarySubtitleOffsetMs }
    }
    val dual by remember(livePlayerState) {
        derivedStateOf { livePlayerState.value.secondarySubtitleTrackId != null }
    }
    val timelineGeneration by remember(livePlayerState) {
        derivedStateOf { livePlayerState.value.diagnostics.outputEvidenceGeneration }
    }

    // Android Lint resolves this commonMain return type as Unit; the cached anchor is immutable.
    @SuppressLint("RememberReturnType")
    val clock: YSubtitleClockAnchor =
        remember<YSubtitleClockAnchor>(clockSample) {
            YSubtitleClockAnchor(
                clockSample.positionMs,
                System.nanoTime(),
                clockSample.advancing,
                clockSample.speed,
            )
        }

    // Whether an ASS event is on screen decided whether a per-frame loop runs at all, and it was
    // answered by scanning both whole tracks on every tick. Merged once per track, it is a
    // binary search over disjoint windows instead.
    val primaryAss = remember(primaryCues) { AssActivityWindows(primaryCues) }
    val secondaryAss = remember(secondaryCues) { AssActivityWindows(secondaryCues) }
    // Preserve the shared clock for mixed ASS/text tracks and their boundary timing.
    val animateFrames =
        visible &&
            LocalRouteVisible.current &&
            clock.advancing &&
            (
                primaryAss.activeAt((clock.positionMs - offsetMs) * MICROS_PER_MILLISECOND) ||
                    secondaryAss.activeAt((clock.positionMs - secondaryOffsetMs) * MICROS_PER_MILLISECOND)
            )
    BoxWithConstraints(modifier) {
        val viewport = DpSize(maxWidth, maxHeight)
        val channel: @Composable (Boolean) -> Unit = { secondary ->
            Core2SubtitleChannel(
                cues = if (secondary) secondaryCues else primaryCues,
                clock = clock,
                animateFrames = animateFrames,
                timelineGeneration = timelineGeneration,
                canvasSize = canvasSize,
                offsetMs = if (secondary) secondaryOffsetMs else offsetMs,
                scale = if (secondary) secondaryScale else scale,
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
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
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
    clock: YSubtitleClockAnchor,
    animateFrames: Boolean,
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
    // Keyed on the track alone: re-created on every clock tick, this state could never hold a
    // previous cue set to compare against, so the comparison below would never save anything.
    var activeCues by remember(timeline) {
        mutableStateOf(timeline.activeAt(clock.positionMs * MICROS_PER_MILLISECOND, offsetMs * MICROS_PER_MILLISECOND))
    }
    val assRenderer = remember { AndroidAssSubtitleRenderer() }
    DisposableEffect(assRenderer) { onDispose(assRenderer::close) }
    val assOverrides = remember(appearance) { appearance.assStyleOverrides() }
    LaunchedEffect(assRenderer, cues, clock, animateFrames, timelineGeneration, offsetMs, canvasSize, assOverrides) {
        fun submit(positionMs: Long) {
            // The loop below runs at display rate. Writing a freshly built list back every frame
            // recomposed this channel sixty times a second for a caption that had not changed;
            // only a different set of cues is a change worth publishing.
            val next = timeline.activeAt(positionMs * MICROS_PER_MILLISECOND, offsetMs * MICROS_PER_MILLISECOND)
            if (!sameSubtitleCues(activeCues, next)) activeCues = next
            assRenderer.submit(
                cues,
                (positionMs - offsetMs) * MICROS_PER_MILLISECOND,
                canvasSize.width,
                canvasSize.height,
                assOverrides,
                timelineGeneration = timelineGeneration,
            )
        }
        submit(clock.positionMs)
        // Start with the same active-ASS gate as before; the next engine tick handles a new cue.
        if (animateFrames) {
            while (true) withFrameNanos { submit(clock.positionAt(it)) }
        }
    }
    val activeText = remember(activeCues) { activeCues.mapNotNull { it.payload as? YSubtitlePayload.Text } }
    val bitmapPayloads = remember(activeCues) { activeCues.mapNotNull { it.payload as? YSubtitlePayload.BitmapArgb } }
    val hasAss = activeCues.any { it.payload is YSubtitlePayload.AssEvent }
    val bitmapFrames = rememberSubtitleBitmapFrames(assRenderer, bitmapPayloads, hasAss)
    val bitmapContent: @Composable () -> Unit = {
        SubtitleBitmapCanvas(bitmapFrames, scale, dual, viewport, position, brightness, appearance)
    }
    if (dual) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            bitmapContent()
            run {
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
            val groupedText = activeText.groupBy { core2SubtitleAlignment(it.style.alignment, secondary, dual) }
            val alignments = remember { mutableSetOf<Int>() }
            alignments.addAll(groupedText.keys)
            alignments.forEach { alignmentCode ->
                val payloads = groupedText[alignmentCode].orEmpty()
                Core2SubtitleText(
                    payloads,
                    alignmentCode,
                    scale,
                    brightness,
                    appearance,
                    Modifier
                        .align(alignmentCode.toComposeAlignment())
                        .fillMaxWidth(0.92f)
                        .padding(horizontal = 12.dp)
                        .then(
                            when {
                                alignmentCode <= 3 ->
                                    Modifier.padding(
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

/** The clock inputs, and only those, so an unrelated diagnostic change cannot re-anchor captions. */
private data class Core2SubtitleClockSample(
    val positionMs: Long,
    val advancing: Boolean,
    val speed: Float,
)

/**
 * Merged, disjoint windows in which a track has an ASS event on screen.
 *
 * The windows are built once per track so the per-frame gate is a binary search rather than a
 * scan of every cue in the file, twice, on every engine tick.
 */
private class AssActivityWindows(
    cues: List<YSubtitleCue>,
) {
    private val starts: LongArray
    private val ends: LongArray

    init {
        val windows = ArrayList<LongArray>()
        cues
            .asSequence()
            .filter { it.payload is YSubtitlePayload.AssEvent && it.endUs > it.startUs }
            .sortedBy(YSubtitleCue::startUs)
            .forEach { cue ->
                val last = windows.lastOrNull()
                if (last != null && cue.startUs <= last[1]) {
                    last[1] = maxOf(last[1], cue.endUs)
                } else {
                    windows.add(longArrayOf(cue.startUs, cue.endUs))
                }
            }
        starts = LongArray(windows.size) { windows[it][0] }
        ends = LongArray(windows.size) { windows[it][1] }
    }

    fun activeAt(timeUs: Long): Boolean {
        if (starts.isEmpty()) return false
        var low = 0
        var high = starts.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= timeUs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return candidate >= 0 && timeUs < ends[candidate]
    }
}

/** Identity and order of the drawn cues; a cue's payload never changes in place under one id. */
private fun sameSubtitleCues(
    current: List<YSubtitleCue>,
    next: List<YSubtitleCue>,
): Boolean {
    if (current.size != next.size) return false
    for (index in current.indices) {
        if (current[index].id != next[index].id) return false
    }
    return true
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
    SubtitleHandoff(payloads, modifier) { visiblePayloads ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            visiblePayloads.forEach { payload ->
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

    /**
     * The Surface the current player already renders into.
     *
     * `surfaceChanged` fires for a pure resize too, and the picture rectangle is now animated:
     * re-publishing a video output on every frame of that would hand MediaCodec a new output
     * twenty times in a third of a second for a Surface it never lost.
     */
    private var attached: Surface? = null

    init {
        holder.addCallback(this)
    }

    fun bind(next: YPlayer) {
        if (player === next) return
        detachOutput()
        player = next
        attachCurrentSurface()
    }

    fun setProtectedContent(required: Boolean) {
        if (protectedContent == required) return
        detachOutput()
        protectedContent = required
        setSecure(required)
        attachCurrentSurface()
    }

    fun unbind() {
        detachOutput()
        player = null
        holder.removeCallback(this)
    }

    private fun detachOutput() {
        attached = null
        player?.setVideoOutput(null)
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
        detachOutput()
    }

    private fun attachCurrentSurface() {
        val surface = holder.surface
        if (!surface.isValid) return
        val target = player ?: return
        if (attached === surface) return
        attached = surface
        target.setVideoOutput(
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
