package com.yfuse.feature.player

import android.graphics.Bitmap
import android.view.OrientationEventListener
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.CURTAIN_GATE_EARLIEST
import com.yfuse.core.designsystem.CURTAIN_GATE_LATEST
import com.yfuse.core.designsystem.CURTAIN_GATE_OPEN
import com.yfuse.core.designsystem.HandoffBox
import com.yfuse.core.designsystem.HandoffInOut
import com.yfuse.core.designsystem.HandoffLaunch
import com.yfuse.core.designsystem.HandoffLinear
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.PlayerHandoff
import com.yfuse.core.designsystem.PlayerTransitionStyle
import com.yfuse.core.designsystem.ScreenGeometry
import com.yfuse.core.designsystem.coveringTurn
import com.yfuse.core.designsystem.drawArtwork
import com.yfuse.core.designsystem.drawGlassRim
import com.yfuse.core.designsystem.drawLamp
import com.yfuse.core.designsystem.drawThroughLens
import com.yfuse.core.designsystem.fittedPicture
import com.yfuse.core.designsystem.glassBarOnScreen
import com.yfuse.core.designsystem.handoffPlayerLag
import com.yfuse.core.designsystem.handoffSegment
import com.yfuse.core.designsystem.inBox
import com.yfuse.core.designsystem.keyCorner
import com.yfuse.core.designsystem.lerpBox
import com.yfuse.core.designsystem.localPath
import com.yfuse.core.designsystem.localRoundRect
import com.yfuse.core.designsystem.mixFloat
import com.yfuse.core.designsystem.physicalBox
import com.yfuse.core.designsystem.pushScale
import com.yfuse.core.designsystem.rememberArtworkPainter
import com.yfuse.core.designsystem.rememberScreenGeometrySource
import com.yfuse.core.designsystem.timing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

internal const val PLAYER_ARTWORK_TOKEN = "yfuse.player.artworkToken"

/**
 * The player window's half of a transition, owned by the Activity.
 *
 * It outlives the preparation → player composition handoff, so the same clock and the same
 * picture carry through whichever composition is up. Times are on the launch's clock: the page
 * started its half at 0, and [playerTime] is that clock shifted by however late this window
 * arrived, so a slow first frame never starts the player mid-turn.
 */
@Stable
internal class PlayerTransitionState(
    val launch: HandoffLaunch,
    private val scope: CoroutineScope,
) {
    val style: PlayerTransitionStyle get() = launch.style
    private val timing = launch.style.timing

    /** The launch clock, ms; advanced once a frame while anything is moving. */
    var now by mutableFloatStateOf(0f)
        private set

    private var lag: Float? = null
    private var readyAt: Float? = null

    /** When the stand-in gave up waiting for a late picture; see [tick]. Read by the host's composition. */
    private var lateAt by mutableStateOf<Float?>(null)
    private var turnedAt: Float? = null
    private var gateAt: Float? = null
    private var closingAt: Float? = null

    var exitAt by mutableStateOf<Float?>(null)
        private set
    var exitFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var closing by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var backProgress by mutableFloatStateOf(0f)
        private set
    var backActive by mutableStateOf(false)
        private set
    var backAtCommit = 0f
        private set

    /** The way in is over: the stand-ins are gone and nothing is left to draw until the way out. */
    var settled by mutableStateOf(false)
        private set

    /** 减弱动态效果 or 画中画 turned up mid-way: stop drawing and let the page go. */
    var disabled = false
        private set

    /** Reads the paused frame off the video surface; set by the composition that owns it. */
    var snapshotSource: (suspend () -> ImageBitmap?)? = null

    private var completeExit: (() -> Unit)? = null
    private var capturing = false
    private var smallFrame: ImageBitmap? = null

    val playerTime: Float get() = now - (lag ?: 0f)

    /** ms since the way out began; negative until then (including while the gesture is live). */
    val exitTime: Float get() = exitAt?.let { now - it } ?: -1f

    /**
     * Advances the clock. [handsOverLate] is set by a host with a continuity overlay under the
     * stand-in: a picture still not ready once the stand-in has landed is then no longer waited
     * for, and the overlay — the one surface that can say the network is why — takes over.
     */
    fun tick(handsOverLate: Boolean) {
        now = launch.elapsedMs()
        if (lag == null) lag = handoffPlayerLag(timing, now)
        if (style == PlayerTransitionStyle.Curtain && gateAt == null) {
            val turned = turnedAt
            gateAt =
                when {
                    turned != null -> max(turned, CURTAIN_GATE_EARLIEST.toFloat())
                    playerTime >= CURTAIN_GATE_LATEST -> CURTAIN_GATE_LATEST.toFloat()
                    else -> null
                }
        }
        if (handsOverLate && readyAt == null && lateAt == null && playerTime >= landAt() + HANDOFF_DELAY_MS) {
            lateAt = playerTime
        }
        if (!backActive && !closing && backProgress > 0f) {
            backProgress = (backProgress - FRAME_MS / BACK_CANCEL_MS).coerceAtLeast(0f)
        }
        if (!settled && entered()) settled = true
        if (exitTime >= timing.exitFinish && !finished) finish()
    }

    /** False once nothing on either layer will change until an input arrives. */
    fun needsFrames(): Boolean =
        !finished &&
            (closing || backActive || backProgress > 0f || !entered())

    private fun entered(): Boolean {
        val handoff = handoffAt() ?: return false
        val tail = if (style == PlayerTransitionStyle.Defocus) FIELD_FADE_MS else 0f
        return playerTime > handoff + timing.pictureHandoff + tail
    }

    fun markReady() {
        if (readyAt == null) readyAt = playerTime
    }

    fun markTurned() {
        if (turnedAt == null) turnedAt = playerTime
    }

    fun gate(): Float? = gateAt

    /** When the picture has landed in its frame, on the player's clock. */
    fun landAt(): Float =
        if (style == PlayerTransitionStyle.Curtain) {
            (gateAt ?: CURTAIN_GATE_LATEST.toFloat()) + CURTAIN_GATE_OPEN
        } else {
            timing.land.toFloat()
        }

    /**
     * When the stand-in starts handing over to the live picture — or, once it has stopped waiting,
     * to the continuity overlay; null until one of the two.
     */
    fun handoffAt(): Float? {
        lateAt?.let { return it }
        val ready = readyAt ?: return null
        if (style == PlayerTransitionStyle.Curtain) {
            val gate = gateAt ?: return null
            // Ready before the gate opened: the gate opens straight onto the video.
            if (ready <= gate + GATE_OPENING_MS) return gate + GATE_OPENING_MS
        }
        return max(ready, landAt()) + HANDOFF_DELAY_MS
    }

    /** Whether the gate opened straight onto a picture that was already playing. */
    fun opensOnVideo(): Boolean {
        val gate = gateAt ?: return false
        val ready = readyAt ?: return false
        return ready <= gate + GATE_OPENING_MS
    }

    /** How much of the player's own chrome may show. */
    fun chromeAlpha(): Float {
        if (disabled || finished) return 1f
        closingAt?.let { return 1f - handoffSegment(now - it, 0f, CHROME_OUT_MS) }
        if (backActive || backProgress > 0f) return 1f - backProgress
        val curtain = style == PlayerTransitionStyle.Curtain
        val chromeIn = if (curtain) landAt() + CURTAIN_CHROME_AFTER_LAND else timing.chromeIn.toFloat()
        return handoffSegment(playerTime, chromeIn, Motion.STANDARD.toFloat())
    }

    /**
     * True while the stand-in still covers the video surface. A late picture's continuity overlay
     * is let in as the stand-in starts to leave, so the two cross rather than dipping to black.
     */
    fun coversPicture(): Boolean = !disabled && !finished && (closing || (lateAt == null && !entered()))

    fun onBackProgress(progress: Float) {
        if (disabled || closing || finished) return
        backActive = true
        backProgress = progress.coerceIn(0f, 1f) * GESTURE_REACH
        captureFrame()
    }

    fun onBackCancel() {
        backActive = false
    }

    /**
     * Starts the way out: the chrome leaves at once, the paused frame is copied (at most a few
     * frames), then both halves run to [action], which finishes the Activity. False when there is
     * nothing to draw — the caller then finishes the plain way.
     */
    fun requestExit(action: () -> Unit): Boolean {
        if (disabled || finished) return false
        if (closing) return true
        closing = true
        closingAt = launch.elapsedMs()
        backAtCommit = backProgress
        backActive = false
        completeExit = action
        scope.launch {
            val frame = exitFrame ?: withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { snapshotSource?.invoke() }
            exitFrame = frame
            smallFrame = frame?.let(::shrink)
            exitAt = launch.elapsedMs()
        }
        return true
    }

    fun disable() {
        if (disabled) return
        disabled = true
        PlayerHandoff.release(launch)
    }

    private fun captureFrame() {
        if (exitFrame != null || capturing) return
        val source = snapshotSource ?: return
        capturing = true
        scope.launch {
            exitFrame = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { source() }
            capturing = false
        }
    }

    private fun finish() {
        finished = true
        PlayerHandoff.comeBack(launch, smallFrame)
        completeExit?.also { completeExit = null }?.invoke()
    }

    val smallExitFrame: ImageBitmap? get() = smallFrame
}

/** A copy a few dozen pixels wide: the colours 虚焦 dissolves the page back out of. */
private fun shrink(frame: ImageBitmap): ImageBitmap? =
    runCatching {
        val source = frame.asAndroidBitmap()
        val height = (SMALL_FRAME_WIDTH * source.height / source.width.coerceAtLeast(1)).coerceAtLeast(1)
        Bitmap.createScaledBitmap(source, SMALL_FRAME_WIDTH, height, true).asImageBitmap()
    }.getOrNull()

/** Which half of the transition a [PlayerTransitionLayer] call draws. */
internal enum class PlayerTransitionLayerKind {
    /** Both halves, for a host with no chrome of its own (the preparation screen). */
    Full,

    /** The way in, under the chrome, so the back key stays reachable while the picture is prepared. */
    Entrance,

    /** The way out and the back gesture, over the chrome. */
    Exit,
}

/**
 * Draws [state]'s half of the transition. The [PlayerTransitionLayerKind.Full] and
 * [PlayerTransitionLayerKind.Entrance] calls also run the clock; an Exit call only draws.
 */
@Composable
internal fun PlayerTransitionLayer(
    state: PlayerTransitionState?,
    ready: Boolean,
    inPictureInPicture: Boolean,
    aspectRatio: Float? = null,
    layer: PlayerTransitionLayerKind = PlayerTransitionLayerKind.Full,
) {
    if (state == null) return
    val disabled = LocalAccessibilityOptions.current.reduceMotion || inPictureInPicture
    SideEffect { if (disabled) state.disable() }
    if (disabled || state.disabled || state.finished) return
    val drives = layer != PlayerTransitionLayerKind.Exit
    if (drives) {
        // The preparation screen has nothing under the stand-in to hand a late picture to; the
        // player's own entrance has the continuity overlay.
        val handsOverLate = layer == PlayerTransitionLayerKind.Entrance
        LaunchedEffect(state, state.closing, state.backActive) {
            while (true) {
                withFrameMillis { }
                state.tick(handsOverLate)
                if (!state.needsFrames()) break
            }
        }
        LaunchedEffect(state, ready) { if (ready) state.markReady() }
        if (state.style == PlayerTransitionStyle.Curtain && state.gate() == null) OrientationGate(state)
    }

    val art = rememberArtworkPainter(state.launch.urls)
    val defocus = state.style == PlayerTransitionStyle.Defocus
    val small = if (defocus) rememberArtworkPainter(state.launch.urls, small = true) else null
    val frame = state.exitFrame
    val framePainter = remember(frame) { frame?.let(::BitmapPainter) }
    val smallFrame = state.smallExitFrame
    val smallFramePainter = remember(smallFrame) { smallFrame?.let(::BitmapPainter) }
    val play = rememberVectorPainter(AppIcons.Play)
    val artLayer = rememberGraphicsLayer()
    val fieldLayer = rememberGraphicsLayer()
    val screenSource = rememberScreenGeometrySource()
    val scene = remember(state) { PlayerScene(state) }
    scene.art = art
    scene.small = small
    scene.frame = framePainter
    scene.smallFrame = smallFramePainter
    scene.play = play
    scene.aspect = aspectRatio
    scene.artLayer = artLayer
    scene.fieldLayer = fieldLayer

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val screen = screenSource.current()
                scene.screen = screen
                scene.origin = it.positionInWindow() + screen.windowOffset
            }.drawBehind {
                when (layer) {
                    PlayerTransitionLayerKind.Entrance -> scene.drawEntrance(this)
                    PlayerTransitionLayerKind.Exit -> scene.drawExit(this)
                    PlayerTransitionLayerKind.Full -> {
                        scene.drawEntrance(this)
                        scene.drawExit(this)
                    }
                }
            },
    ) {
        // Composed only while it could be seen: an orb left in place would animate for the whole film.
        if (layer != PlayerTransitionLayerKind.Exit && !state.settled && !state.closing) {
            val ring = state.style == PlayerTransitionStyle.Glass
            OrbProgress(
                size = if (ring) 60.dp else 28.dp,
                color = Color.White,
                contentDescription = "正在准备画面",
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = scene.orbAlpha() },
            )
        }
    }
}

/**
 * 开幕 opens once the phone has actually been turned to the player's orientation. Flat on a
 * table the sensor has no answer, and the gate opens on its timeout instead.
 */
@Composable
private fun OrientationGate(state: PlayerTransitionState) {
    val context = LocalContext.current
    val screenSource = rememberScreenGeometrySource()
    DisposableEffect(state, context) {
        val playerRotation = screenSource.current().rotation
        val target = (360 - 90 * playerRotation).mod(360)
        val listener =
            object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val distance = abs(orientation - target).let { minOf(it, 360 - it) }
                    if (distance <= TURNED_WITHIN_DEGREES) {
                        state.markTurned()
                        // One answer is all the gate needs; the sensor must not run for the whole film.
                        disable()
                    }
                }
            }
        if (playerRotation == state.launch.screen.rotation) {
            // A tablet or a television: the two windows share an orientation, nothing to wait for.
            state.markTurned()
        } else if (listener.canDetectOrientation()) {
            listener.enable()
        }
        onDispose { listener.disable() }
    }
}

/** Everything one launch draws in the player window, with its geometry resolved per frame. */
private class PlayerScene(
    val state: PlayerTransitionState,
) {
    var art: Painter? = null
    var small: Painter? = null
    var frame: Painter? = null
    var smallFrame: Painter? = null
    var play: Painter? = null
    var aspect: Float? = null
    var origin = Offset.Zero
    var screen: ScreenGeometry? = null
    lateinit var artLayer: GraphicsLayer
    lateinit var fieldLayer: GraphicsLayer

    private val launch get() = state.launch
    private val style get() = state.style

    // ------------------------------------------------------------ geometry

    private fun DrawScope.video(): HandoffBox = fittedPicture(size, aspect)

    private fun DrawScope.whole(): HandoffBox = HandoffBox(center, size.width, size.height)

    /** A rectangle from the page's screen, drawn here on the same patch of glass. */
    private fun DrawScope.map(
        rectOnPage: Rect,
        corner: Float = 0f,
    ): HandoffBox {
        val target = screen
        val mapped = if (target != null) physicalBox(rectOnPage, launch.screen, target, corner) else null
        return mapped?.shifted(-origin) ?: HandoffBox(center, rectOnPage.width, rectOnPage.height, 0f, corner)
    }

    private fun DrawScope.mapPoint(point: Offset): Offset = map(Rect(point, point)).center

    private fun DrawScope.gestureBox(progress: Float): HandoffBox =
        video().scaled(1f - 0.1f * progress).copy(
            center = video().center + Offset(16f * density * progress, 0f),
            corner = 24f * density * progress,
        )

    private fun DrawScope.backdrop(te: Float) {
        val alpha = if (te < 0f) state.backProgress else max(state.backAtCommit, handoffSegment(te, 0f, 120f))
        drawRect(Color.Black, alpha = alpha.coerceIn(0f, 1f))
    }

    fun orbAlpha(): Float {
        val handoff = state.handoffAt()
        val land = state.landAt()
        if (state.closing || (handoff != null && handoff <= land + 40f)) return 0f
        val tp = state.playerTime
        val start = land + if (style == PlayerTransitionStyle.Turn) 0f else 40f
        val shown = handoffSegment(tp, start, 200f)
        val gone = if (handoff != null) handoffSegment(tp, handoff, 160f) else 0f
        return shown * (1f - gone)
    }

    // ------------------------------------------------------------ dispatch

    fun drawEntrance(scope: DrawScope) {
        if (state.closing || state.finished) return
        val tp = state.playerTime
        with(scope) {
            when (style) {
                PlayerTransitionStyle.Turn -> turnIn(tp)
                PlayerTransitionStyle.Curtain -> curtainIn(tp)
                PlayerTransitionStyle.Glass -> glassIn(tp)
                PlayerTransitionStyle.PushIn -> pushIn(tp)
                PlayerTransitionStyle.Tide -> tideIn(tp)
                PlayerTransitionStyle.Defocus -> defocusIn(tp)
                PlayerTransitionStyle.None -> Unit
            }
        }
    }

    fun drawExit(scope: DrawScope) {
        val gesture = state.backActive || state.backProgress > 0f
        if (!state.closing && !gesture) return
        val te = state.exitTime
        with(scope) {
            if (state.closing && te < 0f && state.backAtCommit <= 0f) {
                // Copying the paused frame: hold the picture still under a black that is arriving.
                return
            }
            when (style) {
                PlayerTransitionStyle.Turn -> turnOut(te)
                PlayerTransitionStyle.Curtain -> curtainOut(te)
                PlayerTransitionStyle.Glass -> glassOut(te)
                PlayerTransitionStyle.PushIn -> pushOut(te)
                PlayerTransitionStyle.Tide -> tideOut(te)
                PlayerTransitionStyle.Defocus -> defocusOut(te)
                PlayerTransitionStyle.None -> Unit
            }
        }
    }

    /** Black over the surface until the stand-in starts handing over. */
    private fun DrawScope.cover(tp: Float) {
        val handoff = state.handoffAt()
        if (handoff == null || tp < handoff) drawRect(Color.Black)
    }

    private fun DrawScope.handoffFraction(
        tp: Float,
        duration: Float = style.timing.pictureHandoff.toFloat(),
        easing: androidx.compose.animation.core.Easing = Motion.Curve,
    ): Float = state.handoffAt()?.let { handoffSegment(tp, it, duration, easing) } ?: 0f

    // ------------------------------------------------------------ 转身

    private fun DrawScope.liftedHero(): HandoffBox = map(launch.hero, 22f * density).scaled(0.96f)

    private fun DrawScope.turnIn(tp: Float) {
        cover(tp)
        val box = lerpBox(liftedHero(), video(), handoffSegment(tp, 260f, 640f), handoffSegment(tp, 260f, 520f))
        val f = handoffFraction(tp)
        val zoom = 1f + 0.025f * handoffSegment(tp, 900f, 4000f, HandoffLinear)
        drawBlurred(artLayer, art, box, 1f - f, zoom, blur = 10f * density * f)
    }

    private fun DrawScope.turnOut(te: Float) {
        backdrop(te)
        val box =
            if (te < 0f) {
                gestureBox(state.backProgress)
            } else {
                lerpBox(
                    gestureBox(state.backAtCommit),
                    liftedHero(),
                    handoffSegment(te, 60f, 580f),
                    handoffSegment(te, 60f, 500f),
                )
            }
        // Mid-gesture only the paused frame is carried; the poster underneath is for the way back.
        if (te >= 0f) drawArtwork(art, box)
        drawArtwork(frame, box, if (te < 0f) 1f else 1f - handoffSegment(te, 300f, 320f))
    }

    // ------------------------------------------------------------ 开幕

    private fun DrawScope.curtainIn(tp: Float) {
        val video = video()
        val gate = state.gate()
        val lampFrom = mapPoint(launch.origin)
        val glide = handoffSegment(tp, 460f, 400f)
        val lampAt = Offset(mixFloat(lampFrom.x, video.center.x, glide), mixFloat(lampFrom.y, video.center.y, glide))
        val breath = 1f + 0.12f * sin(tp / 1100f * 2f * PI.toFloat())
        if (gate == null || tp < gate) {
            drawRect(Color.Black)
            drawLamp(lampAt, 1f, breath)
            return
        }
        val width = video.width * handoffSegment(tp, gate, 220f)
        val height = mixFloat(2f * density, video.height, handoffSegment(tp, gate + GATE_OPENING_MS, 480f))
        val opening = gateRect(video, width, height)
        val handoff = state.handoffAt()
        if (!state.opensOnVideo()) {
            val shown = if (handoff != null) 1f - handoffSegment(tp, handoff, 320f) else 1f
            clipRect(opening.left, opening.top, opening.right, opening.bottom) { drawArtwork(art, video, shown) }
        }
        val warm = 0.55f * (1f - handoffSegment(tp, gate + GATE_OPENING_MS, 700f))
        val pulse = handoff?.let { handoffSegment(tp, it, 320f, HandoffLinear) } ?: 0f
        val bump = 0.14f * sin(PI.toFloat() * pulse)
        drawRect(WARM_VEIL, topLeft = opening.topLeft, size = opening.size, alpha = max(warm, bump))
        masks(opening)
        if (tp >= gate + GATE_OPENING_MS) edges(opening, 1f - handoffSegment(tp, gate + 520f, 300f))
        val slit =
            handoffSegment(tp, gate, 60f, HandoffLinear) * (1f - handoffSegment(tp, gate + GATE_OPENING_MS, 160f))
        slit(video.center, width, slit)
        drawLamp(lampAt, 1f - handoffSegment(tp, gate, 120f, HandoffLinear), breath)
    }

    private fun DrawScope.curtainOut(te: Float) {
        val video = video()
        val p = state.backProgress
        val p0 = state.backAtCommit
        val height: Float
        val dark: Float
        val edges: Float
        if (te < 0f) {
            height = video.height * (1f - 0.24f * p)
            dark = 0.25f * p
            edges = p
        } else {
            val close = handoffSegment(te, 80f, 380f, Motion.Dialog.ExitCurve)
            height = mixFloat(video.height * (1f - 0.24f * p0), 2f * density, close)
            dark = mixFloat(0.25f * p0, 0.45f, close)
            edges = if (te < 440f) max(if (p0 > 0f) 1f else 0f, handoffSegment(te, 80f, 120f)) else 0f
        }
        val width = video.width * (1f - handoffSegment(te, 440f, 160f))
        val opening = gateRect(video, width, height)
        drawRect(Color.Black, topLeft = opening.topLeft, size = opening.size, alpha = dark)
        masks(opening)
        if (height > 6f * density) edges(opening, edges)
        val back = handoffSegment(te, 560f, 240f)
        val home = mapPoint(launch.origin)
        val point = Offset(mixFloat(video.center.x, home.x, back), mixFloat(video.center.y, home.y, back))
        val line = if (te < 440f) 0f else 1f - handoffSegment(te, 560f, 60f, HandoffLinear)
        slit(point, width, line)
        drawLamp(point, handoffSegment(te, 540f, 80f, HandoffLinear))
    }

    private fun gateRect(
        video: HandoffBox,
        width: Float,
        height: Float,
    ): Rect =
        Rect(
            video.center.x - width / 2f,
            video.center.y - height / 2f,
            video.center.x + width / 2f,
            video.center.y + height / 2f,
        )

    /** Black everywhere but the gate — the surface is under this layer, so these are the curtains. */
    private fun DrawScope.masks(opening: Rect) {
        clipRect(opening.left, opening.top, opening.right, opening.bottom, ClipOp.Difference) {
            drawRect(Color.Black)
        }
    }

    private fun DrawScope.edges(
        opening: Rect,
        alpha: Float,
    ) {
        if (alpha <= 0f || opening.width <= 0f) return
        for (y in listOf(opening.top, opening.bottom)) {
            val glow = 18f * density
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to EDGE_GLOW.copy(alpha = 0.55f * alpha),
                    1f to Color.Transparent,
                    startY = y - glow,
                    endY = y + glow,
                ),
                topLeft = Offset(opening.left, y - glow),
                size = Size(opening.width, glow * 2f),
            )
            drawRect(
                EDGE_CORE,
                topLeft = Offset(opening.left, y - density),
                size = Size(opening.width, 2f * density),
                alpha = alpha,
            )
        }
    }

    private fun DrawScope.slit(
        center: Offset,
        width: Float,
        alpha: Float,
    ) {
        if (alpha <= 0f || width <= 2f) return
        val glow = 20f * density
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to EDGE_GLOW.copy(alpha = 0.8f * alpha),
                1f to Color.Transparent,
                startY = center.y - glow,
                endY = center.y + glow,
            ),
            topLeft = Offset(center.x - width / 2f, center.y - glow),
            size = Size(width, glow * 2f),
        )
        drawRect(
            EDGE_CORE,
            topLeft = Offset(center.x - width / 2f, center.y - 1.5f * density),
            size = Size(width, 3f * density),
            alpha = alpha,
        )
    }

    // ------------------------------------------------------------ 玻璃舱

    private fun DrawScope.bar(): HandoffBox = map(launch.glassBarOnScreen(density), launch.keyCorner(density))

    private fun DrawScope.glassIn(tp: Float) {
        cover(tp)
        val video = video()
        if (tp < GLASS_LAND) {
            val move = handoffSegment(tp, 320f, 660f)
            val lens = lerpBox(bar(), video, move, handoffSegment(tp, 320f, 520f))
            drawThroughLens(art, video, lens, mixFloat(1.1f, 1f, move))
            glassFill(lens, 1f - handoffSegment(tp, 320f, 380f))
            drawGlassRim(lens, 1f - handoffSegment(tp, 760f, 220f))
            well(lens.center, lens.rotation, mixFloat(16f, 26f, move) * density, 1f)
            return
        }
        val sweep = handoffFraction(tp, easing = HandoffInOut)
        val band = video.localLeft() - 80f * density + (video.width + 160f * density) * sweep
        if (sweep < 1f) {
            clipRect(left = band.coerceAtLeast(video.localLeft())) { drawArtwork(art, video) }
            if (sweep > 0f) sweepBand(band, video)
        }
        val wellAlpha = 1f - ((band - video.center.x + 30f * density) / (90f * density)).coerceIn(0f, 1f)
        well(video.center, 0f, 26f * density, wellAlpha)
    }

    private fun DrawScope.glassOut(te: Float) {
        backdrop(te)
        val video = video()
        val p = state.backProgress
        val p0 = state.backAtCommit
        val lens: HandoffBox
        val rim: Float
        var zoom = 1f
        var tint = 0f
        var wellAlpha = 0f
        var wellRadius = 26f * density
        if (te < 0f) {
            lens = video.scaled(1f - 0.1f * p).copy(corner = 28f * density * p)
            rim = p
        } else {
            val start = video.scaled(1f - 0.1f * p0).copy(corner = 28f * density * p0)
            val move = handoffSegment(te, 100f, 600f)
            val bar = bar()
            val moved = lerpBox(start, bar, move, handoffSegment(te, 100f, 520f))
            val rounded =
                mixFloat(mixFloat(start.corner, 16f * density, handoffSegment(te, 60f, 200f)), bar.corner, move)
            lens = moved.copy(corner = rounded)
            rim = max(p0, handoffSegment(te, 60f, 200f))
            zoom = mixFloat(1f, 1.1f, move)
            tint = handoffSegment(te, 420f, 280f)
            wellAlpha = handoffSegment(te, 460f, 180f)
            wellRadius = mixFloat(26f, 16f, move) * density
        }
        drawThroughLens(carried(te), video, lens, zoom)
        glassFill(lens, tint)
        drawGlassRim(lens, rim)
        well(lens.center, lens.rotation, wellRadius, wellAlpha)
    }

    /** The key's glass body — the same composition the page's bar has once it is fully glass. */
    private fun DrawScope.glassFill(
        box: HandoffBox,
        alpha: Float,
    ) {
        if (alpha <= 0f) return
        val key = launch.key
        val tint = key?.tint
        val ink = key?.ink ?: Color.White
        val clear = key?.glass ?: (tint == null)
        inBox(box) {
            clipPath(box.localPath()) {
                val outline = box.localRoundRect()
                val topLeft = Offset(outline.left, outline.top)
                val area = Size(outline.width, outline.height)
                drawRect(GLASS_BASE, topLeft = topLeft, size = area, alpha = alpha)
                if (clear) {
                    drawRect(GLASS_CLEAR, topLeft = topLeft, size = area, alpha = 0.5f * alpha)
                } else if (tint != null) {
                    drawRect(
                        Brush.linearGradient(
                            0f to lerp(tint, ink, 0.1f),
                            1f to tint,
                            start = topLeft,
                            end = Offset(outline.right, outline.bottom),
                        ),
                        topLeft = topLeft,
                        size = area,
                        alpha = 0.3f * alpha,
                    )
                }
            }
        }
    }

    private fun DrawScope.well(
        center: Offset,
        rotation: Float,
        radius: Float,
        alpha: Float,
    ) {
        if (alpha <= 0f) return
        val box = HandoffBox(center, radius * 2f, radius * 2f, rotation, radius)
        inBox(box) {
            drawCircle(Color.White, radius = radius, center = Offset.Zero, alpha = 0.14f * alpha)
        }
        drawGlassRim(box, 0.8f * alpha)
        val icon = play ?: return
        inBox(box) {
            val side = radius * 0.8f
            translate(-side / 2f + radius * 0.06f, -side / 2f) {
                with(icon) { draw(Size(side, side), alpha, ColorFilter.tint(Color.White)) }
            }
        }
    }

    private fun DrawScope.sweepBand(
        x: Float,
        video: HandoffBox,
    ) {
        val half = 66f * density
        val top = video.center.y - video.height / 2f
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.28f to Color(0xFF96C4FF).copy(alpha = 0.12f),
                0.5f to Color.White.copy(alpha = 0.3f),
                0.72f to Color(0xFFFFBE82).copy(alpha = 0.12f),
                1f to Color.Transparent,
                startX = x - half,
                endX = x + half,
            ),
            topLeft = Offset(x - half, top),
            size = Size(half * 2f, video.height),
        )
        drawRect(
            Color.White,
            topLeft = Offset(x - 0.75f * density, top),
            size = Size(1.5f * density, video.height),
            alpha = 0.85f,
        )
    }

    // ------------------------------------------------------------ 推近

    /** The page's end state: the artwork zoomed to cover the page, centred on it. */
    private fun DrawScope.pushed(): HandoffBox {
        val scale = launch.pushScale()
        val page = launch.screen.size
        val half = Offset(launch.hero.width * scale / 2f, launch.hero.height * scale / 2f)
        val center = Offset(page.width / 2f, page.height / 2f)
        return map(Rect(center - half, center + half))
    }

    private fun DrawScope.pushIn(tp: Float) {
        cover(tp)
        val box =
            if (tp < PUSH_TURNED) {
                coveringTurn(handoffSegment(tp, 380f, 540f), pushed(), whole(), size)
            } else {
                val settle = handoffSegment(tp, PUSH_TURNED, 320f)
                lerpBox(whole(), video(), settle)
            }
        val f = handoffFraction(tp)
        drawArtwork(art, box, 1f - f, zoom = 1f + 0.03f * f)
    }

    private fun DrawScope.pushOut(te: Float) {
        backdrop(te)
        val box =
            when {
                te < 0f -> gestureBox(state.backProgress)
                te < PUSH_UNSETTLED -> lerpBox(gestureBox(state.backAtCommit), whole(), handoffSegment(te, 60f, 320f))
                else -> coveringTurn(handoffSegment(te, PUSH_UNSETTLED, 540f), whole(), pushed(), size)
            }
        if (te >= 0f) drawArtwork(art, box)
        drawArtwork(frame, box, if (te < 0f) 1f else 1f - handoffSegment(te, 560f, 320f))
    }

    // ------------------------------------------------------------ 潮汐

    private fun DrawScope.tideIn(tp: Float) {
        cover(tp)
        val tau = tp - TIDE_SURFACE
        if (tau < 0f) return
        var alpha = handoffSegment(tp, TIDE_SURFACE, 300f, HandoffLinear)
        var scale = 1f - 0.08f * exp(-tau / 200f) * cos(PI.toFloat() * tau / 300f)
        val f = handoffFraction(tp)
        alpha *= 1f - f
        scale *= 1f + 0.012f * sin(PI.toFloat() * f)
        drawArtwork(art, video().scaled(scale), alpha)
    }

    private fun DrawScope.tideOut(te: Float) {
        backdrop(te)
        val p = state.backProgress
        val p0 = state.backAtCommit
        val scale: Float
        val alpha: Float
        val drop: Float
        if (te < 0f) {
            scale = 1f - 0.08f * p
            alpha = 1f - 0.3f * p
            drop = 6f * density * p
        } else {
            val sink = handoffSegment(te, 60f, 400f, Motion.Dialog.ExitCurve)
            scale = mixFloat(1f - 0.08f * p0, 0.92f, sink)
            drop = mixFloat(6f * density * p0, 10f * density, sink)
            alpha = (1f - 0.3f * p0) * (1f - handoffSegment(te, 120f, 340f, HandoffLinear))
        }
        drawArtwork(carried(te), video().scaled(scale).shifted(Offset(0f, drop)), alpha)
    }

    // ------------------------------------------------------------ 虚焦

    /** The page's end state: its whole screen, in the artwork's colours. */
    private fun DrawScope.pageField(): HandoffBox {
        val page = launch.screen.size
        return map(Rect(0f, 0f, page.width, page.height))
    }

    private fun DrawScope.defocusIn(tp: Float) {
        cover(tp)
        val focus = handoffSegment(tp, 760f, 480f)
        val handoff = state.handoffAt()
        val after = handoff?.let { handoffSegment(tp, it + style.timing.pictureHandoff, FIELD_FADE_MS) } ?: 0f
        val box = coveringTurn(handoffSegment(tp, 380f, 520f), pageField(), whole(), size)
        val clearVideo = handoff != null && tp >= handoff
        field(
            small,
            box,
            mixFloat(1f, 0.8f, focus) * (1f - after),
            dark = 0.58f * focus,
            keepClear = if (clearVideo) video() else null,
        )
        val shown = focus * (1f - handoffFraction(tp))
        drawBlurred(
            artLayer,
            art,
            video().scaled(mixFloat(1.06f, 1f, focus)),
            shown,
            1f,
            blur =
                18f * density * (1f - focus),
        )
    }

    private fun DrawScope.defocusOut(te: Float) {
        backdrop(te)
        val p = state.backProgress
        val p0 = state.backAtCommit
        val dissolve = if (te < 0f) 0f else handoffSegment(te, 60f, 400f)
        val turn = handoffSegment(te, DEFOCUS_TURN_BACK, 520f)
        val box = coveringTurn(turn, whole(), pageField(), size)
        val fieldAlpha = if (te < 0f) 0.5f * p else max(0.5f * p0, dissolve)
        field(smallFrame ?: small, box, fieldAlpha, dark = 0.58f * (1f - dissolve), keepClear = null)
        val blur = if (te < 0f) 10f * density * p else mixFloat(10f * density * p0, 22f * density, dissolve)
        val zoom = if (te < 0f) 1f - 0.05f * p else mixFloat(1f - 0.05f * p0, 1.15f, dissolve)
        val alpha = if (te < 0f) 1f else 1f - handoffSegment(te, 300f, 200f, HandoffLinear)
        drawBlurred(artLayer, carried(te), video().scaled(zoom), alpha, 1f, blur)
    }

    private fun DrawScope.field(
        painter: Painter?,
        box: HandoffBox,
        alpha: Float,
        dark: Float,
        keepClear: HandoffBox?,
    ) {
        if (alpha <= 0f || painter == null) return
        val blur = FIELD_BLUR_DP * density
        fieldLayer.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
        fieldLayer.alpha = alpha.coerceIn(0f, 1f)
        fieldLayer.record(IntSize(size.width.toInt(), size.height.toInt())) {
            drawArtwork(painter, box.scaled(FIELD_OVERSCAN), 1f, colorFilter = SATURATE_FIELD)
            drawRect(Color.Black, alpha = dark.coerceIn(0f, 1f))
        }
        if (keepClear == null) {
            drawLayer(fieldLayer)
        } else {
            val left = keepClear.center.x - keepClear.width / 2f
            val top = keepClear.center.y - keepClear.height / 2f
            clipRect(left, top, left + keepClear.width, top + keepClear.height, ClipOp.Difference) {
                drawLayer(fieldLayer)
            }
        }
    }

    /** Draws [painter] into [box], blurred on API 31+ (and simply sharp below, where blur is not available). */
    private fun DrawScope.drawBlurred(
        layer: GraphicsLayer,
        painter: Painter?,
        box: HandoffBox,
        alpha: Float,
        zoom: Float,
        blur: Float,
    ) {
        if (alpha <= 0f || painter == null) return
        if (blur <= 0.5f) {
            drawArtwork(painter, box, alpha, zoom)
            return
        }
        layer.renderEffect = BlurEffect(blur, blur, TileMode.Decal)
        layer.alpha = alpha.coerceIn(0f, 1f)
        layer.record(IntSize(size.width.toInt(), size.height.toInt())) { drawArtwork(painter, box, 1f, zoom) }
        drawLayer(layer)
    }

    private fun HandoffBox.localLeft(): Float = center.x - width / 2f

    /** What the way out carries: the paused frame, or the poster once the frame is known to be unreadable. */
    private fun carried(te: Float): Painter? = frame ?: art.takeIf { te >= 0f }
}

private val WARM_VEIL = Color(0xFFFFE8C8)
private val EDGE_GLOW = Color(0xFFFFB066)
private val EDGE_CORE = Color(0xFFFFF4E2)
private val GLASS_BASE = Color(0xFF0B0809)
private val GLASS_CLEAR = Color(0xFF121620)
private val SATURATE_FIELD = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) })

private const val FRAME_MS = 16f
private const val BACK_CANCEL_MS = 220f
private const val GESTURE_REACH = 0.85f
private const val SNAPSHOT_TIMEOUT_MS = 160L
private const val HANDOFF_DELAY_MS = 40f
private const val GATE_OPENING_MS = 180f
private const val CURTAIN_CHROME_AFTER_LAND = 40f
private const val CHROME_OUT_MS = 120f
private const val FIELD_FADE_MS = 600f
private const val FIELD_BLUR_DP = 18f
private const val FIELD_OVERSCAN = 1.18f
private const val GLASS_LAND = 980f
private const val PUSH_TURNED = 920f
private const val PUSH_UNSETTLED = 380f
private const val TIDE_SURFACE = 600f
private const val DEFOCUS_TURN_BACK = 380f
private const val TURNED_WITHIN_DEGREES = 20
private const val SMALL_FRAME_WIDTH = 64
