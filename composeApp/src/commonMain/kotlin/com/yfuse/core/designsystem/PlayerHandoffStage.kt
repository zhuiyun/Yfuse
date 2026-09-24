package com.yfuse.core.designsystem

import androidx.compose.animation.core.Spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The page's half of the player transitions, applied to the app's root content.
 *
 * On a launch the page plays its way out and then holds its last frame for as long as the player
 * is up — the player fades in over, and later out over, exactly that frame. When the player comes
 * back it hands over from the same frame and the page plays its way in. Touches are held while the
 * page is leaving, so a second tap cannot start a second launch under the first; on the way back
 * the page is already the page again, and holding them there kept it out of reach for most of a
 * second after 关闭. Idle, this is the modifier it was applied to and nothing else.
 */
@Composable
internal fun Modifier.playerHandoffStage(): Modifier {
    val phase = PlayerHandoff.phase
    val launch = PlayerHandoff.launch
    if (phase == HandoffPhase.Idle || launch == null) return this

    val clock = remember(launch) { StageClock() }
    val screen = rememberScreenGeometrySource()
    val art = rememberArtworkPainter(launch.urls)
    val defocus = launch.style == PlayerTransitionStyle.Defocus
    val field = if (defocus) rememberArtworkPainter(launch.urls, small = true) else null
    val returnFrame = PlayerHandoff.returnFrame
    val framePainter = remember(returnFrame) { returnFrame?.let(::BitmapPainter) }
    val play = rememberVectorPainter(AppIcons.Play)
    val heroLayer = rememberGraphicsLayer()
    val fieldLayer = rememberGraphicsLayer()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(launch) { runStageClock(launch, clock, lifecycleOwner.lifecycle) }
    DisposableEffect(lifecycleOwner, launch) {
        // Back in front while still "leaving" means the player went away some other way
        // (画中画, a crash, the launcher): nothing will come back through the page, so let it go.
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME &&
                    PlayerHandoff.phase == HandoffPhase.Leaving &&
                    launch.elapsedMs() > ABANDONED_AFTER_MS
                ) {
                    PlayerHandoff.release(launch)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scene = remember(launch) { StageScene(launch) }
    scene.art = art
    scene.field = framePainter ?: field
    scene.leaveField = field
    scene.play = play
    return this
        .pointerInput(launch) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (PlayerHandoff.phase == HandoffPhase.Leaving) event.changes.forEach { it.consume() }
                }
            }
        }.drawWithContent {
            drawContent()
            scene.drawOverlay(this, clock, heroLayer, fieldLayer)
        }.onGloballyPositioned {
            val geometry = screen.current()
            scene.origin = it.positionInWindow() + geometry.windowOffset
            scene.size = it.size
            // Laid out again, on the way back, for a display turned or resized since the launch:
            // the phone was turned during the film, the artwork is no longer where the player
            // just put it down, and a way back from there would land on nothing. Let it go.
            val phase = PlayerHandoff.phase
            if ((phase == HandoffPhase.Returning || phase == HandoffPhase.Releasing) &&
                PlayerHandoff.launch === launch &&
                (geometry.rotation != launch.screen.rotation || geometry.size != launch.screen.size)
            ) {
                PlayerHandoff.settle()
            }
        }.graphicsLayer { scene.transformContent(this, clock) }
}

@Stable
private class StageClock {
    /** ms since the launch. */
    var leave by mutableFloatStateOf(0f)

    /** ms since the page began its way back; negative before. */
    var back by mutableFloatStateOf(-1f)

    /** True once the page is on its way back, whether returning or released. */
    var released by mutableStateOf(false)
}

private suspend fun runStageClock(
    launch: HandoffLaunch,
    clock: StageClock,
    lifecycle: Lifecycle,
) {
    val timing = launch.style.timing
    val leftBy = pageLeftBy(launch.style)
    var backMark: TimeMark? = null
    while (true) {
        withFrameMillis { }
        val sinceLaunch = launch.elapsedMs()
        // Past its last step the page is its held frame: written once, not on every frame.
        val leave = if (sinceLaunch >= leftBy) HELD else sinceLaunch
        if (clock.leave != leave) clock.leave = leave
        when (PlayerHandoff.phase) {
            HandoffPhase.Idle -> return
            HandoffPhase.Leaving ->
                // The player never came: the launch failed, or is stuck behind a dialog. The page
                // cannot sit dimmed under an app that is still in front.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && sinceLaunch > STUCK_AFTER_MS) {
                    PlayerHandoff.release(launch)
                } else if (leave == HELD) {
                    // Nothing on the page changes again until the player comes back, so the page
                    // stops redrawing — a full-screen blur, for two of the sets — while the player
                    // starts up. Only a launch that may yet turn out stuck keeps a deadline.
                    val untilStuck = (STUCK_AFTER_MS - sinceLaunch).toLong()
                    if (untilStuck > 0L) {
                        withTimeoutOrNull(untilStuck) { awaitLeavingEnds() }
                    } else {
                        awaitLeavingEnds()
                    }
                }
            HandoffPhase.Returning, HandoffPhase.Releasing -> {
                val mark = backMark ?: TimeSource.Monotonic.markNow().also { backMark = it }
                val elapsed = mark.elapsedNow().inWholeMicroseconds / 1000f
                val releasing = PlayerHandoff.phase == HandoffPhase.Releasing
                clock.released = true
                // A release runs the same way back, compressed: nothing to meet, only to undo.
                clock.back = if (releasing) elapsed * timing.pageReturn / RELEASE_MS else elapsed
                if (clock.back > timing.pageReturn + SETTLE_MARGIN_MS) {
                    PlayerHandoff.settle()
                    return
                }
            }
        }
    }
}

private suspend fun awaitLeavingEnds() {
    snapshotFlow { PlayerHandoff.phase }.first { it != HandoffPhase.Leaving }
}

/**
 * When each set's page has drawn its last change on the way out (the steps in the draw functions
 * below); from there it is the frame it holds. 开幕's glow and 玻璃舱's blur outlast the moment the
 * player takes over, 潮汐's wash is under a near-black dim by its end.
 */
private fun pageLeftBy(style: PlayerTransitionStyle): Float =
    when (style) {
        PlayerTransitionStyle.Curtain -> 480f
        PlayerTransitionStyle.Glass -> 300f
        else -> style.timing.pageHeld.toFloat()
    }

private const val ABANDONED_AFTER_MS = 900f
private const val STUCK_AFTER_MS = 3_000f
private const val RELEASE_MS = 260f
private const val SETTLE_MARGIN_MS = 40f

/** Everything one launch draws on the page, with the page's geometry resolved per frame. */
private class StageScene(
    val launch: HandoffLaunch,
) {
    var origin = Offset.Zero
    var size = IntSize.Zero
    var art: Painter? = null
    var field: Painter? = null
    var leaveField: Painter? = null
    var play: Painter? = null
    private val blurs = HandoffBlurs(TileMode.Clamp)
    private val saturations = HandoffSaturations()

    private val style get() = launch.style

    private fun hero(): Rect = launch.hero.translate(-origin)

    private fun key(): Rect? = launch.key?.boundsOnScreen?.translate(-origin)

    private fun source(): Offset = launch.origin - origin

    /** Time on the way out; once on the way back, the held end of it. */
    private fun leaving(clock: StageClock): Float = if (clock.released) HELD else clock.leave

    private fun back(clock: StageClock): Float = if (clock.released) clock.back.coerceAtLeast(0f) else -1f

    fun transformContent(
        layer: GraphicsLayerScope,
        clock: StageClock,
    ) = with(layer) {
        val t = leaving(clock)
        val u = back(clock)
        val returning = u >= 0f
        when (style) {
            PlayerTransitionStyle.Curtain -> {
                val k = if (returning) 1f - handoffSegment(u, 100f, 520f) else handoffSegment(t, 0f, 380f)
                val s = 1f - 0.015f * k
                scaleX = s
                scaleY = s
            }
            PlayerTransitionStyle.Glass -> {
                val r = if (returning) 1f - handoffSegment(u, 60f, 400f) else handoffSegment(t, 0f, 300f)
                val s = 1f - 0.06f * r
                scaleX = s
                scaleY = s
                renderEffect = blurs.of(4f * density * r)
            }
            PlayerTransitionStyle.PushIn -> {
                val zoom = pushZoom(this.size)
                val z = if (returning) 1f - handoffSegment(u, 60f, 400f) else handoffSegment(t, 0f, 340f)
                val s = mixFloat(1f, zoom.scale, z)
                scaleX = s
                scaleY = s
                transformOrigin = zoom.origin
            }
            PlayerTransitionStyle.Tide -> tideContent(this, t, u, returning)
            else -> Unit
        }
    }

    private fun tideContent(
        layer: GraphicsLayerScope,
        t: Float,
        u: Float,
        returning: Boolean,
    ) = with(layer) {
        val amplitude = TIDE_AMPLITUDE_DP * density
        val speed = TIDE_SPEED_PX_PER_MS_AT_DP * density
        if (supportsTideShader) {
            renderEffect =
                tideRenderEffect(this.size, source(), if (returning) u else t, speed, amplitude, returning)
        } else if (returning) {
            // Below API 33 the page rises as one piece.
            translationY = amplitude * 0.9f * exp(-u / TIDE_DECAY_MS) * cos(2f * PI.toFloat() * u / TIDE_PERIOD_MS)
            alpha = handoffSegment(u, 0f, 300f, HandoffLinear)
        } else {
            translationY = amplitude * 0.6f * tideCrest(t - 60f)
        }
    }

    fun drawOverlay(
        scope: ContentDrawScope,
        clock: StageClock,
        heroLayer: GraphicsLayer,
        fieldLayer: GraphicsLayer,
    ) {
        val t = leaving(clock)
        val u = back(clock)
        with(scope) {
            when (style) {
                PlayerTransitionStyle.Turn -> drawTurn(t, u)
                PlayerTransitionStyle.Curtain -> drawCurtain(t, u)
                PlayerTransitionStyle.Glass -> drawGlass(t, u)
                PlayerTransitionStyle.PushIn -> drawPushIn(t, u)
                PlayerTransitionStyle.Tide -> drawTide(t, u)
                PlayerTransitionStyle.Defocus -> drawDefocus(t, u, heroLayer, fieldLayer)
                PlayerTransitionStyle.None -> Unit
            }
        }
    }

    // --------------------------------------------------------------- 转身

    private fun DrawScope.drawTurn(
        t: Float,
        u: Float,
    ) {
        val returning = u >= 0f
        val dim = if (returning) 0.9f * (1f - handoffSegment(u, 60f, 420f)) else 0.9f * handoffSegment(t, 0f, 220f)
        drawRect(Color.Black, alpha = dim)
        val lift =
            if (returning) {
                1f - handoffSpring(u - 60f, SETTLE_DAMPING, Spring.StiffnessMediumLow)
            } else {
                handoffSegment(t, 40f, 220f)
            }
        val copy = if (returning) 1f - handoffSegment(u, 300f, 260f) else handoffSegment(t, 0f, 160f)
        drawArtwork(art, liftedHero(lift), copy)
    }

    /** The artwork as the page lifts it: 4% smaller, its corners rounding to 22 dp. */
    private fun DrawScope.liftedHero(lift: Float): HandoffBox =
        HandoffBox.of(hero(), corner = 22f * density * lift.coerceIn(0f, 1f)).scaled(1f - 0.04f * lift)

    // --------------------------------------------------------------- 开幕

    private fun DrawScope.drawCurtain(
        t: Float,
        u: Float,
    ) {
        val center = source()
        val full = farthestCorner(center) + 230f * density
        val returning = u >= 0f
        val radius: Float
        val feather: Float
        if (returning) {
            val open = handoffSegment(u, 100f, 520f)
            radius = mixFloat(0f, full, open)
            feather = mixFloat(36f, 220f, open) * density
        } else {
            val close = handoffSegment(t, 0f, 380f)
            radius = mixFloat(full, 46f * density, close) * (1f - handoffSegment(t, 380f, 60f))
            feather = mixFloat(220f, 36f, close) * density
        }
        drawIris(center, radius, feather)
        val key = key()
        val glow =
            if (returning) {
                handoffSegment(u, 0f, 120f) * (1f - handoffSegment(u, 80f, 400f))
            } else {
                handoffSegment(t, 0f, 300f) * (1f - handoffSegment(t, 320f, 160f))
            }
        if (key != null && glow > 0f) drawKeyGlow(key, glow)
        val breath = 1f + 0.12f * sin((t / 1100f) * 2f * PI.toFloat())
        val lamp = if (returning) 1f - handoffSegment(u, 80f, 160f) else handoffSegment(t, 280f, 100f)
        drawLamp(center, lamp, breath)
    }

    private fun DrawScope.drawIris(
        center: Offset,
        radius: Float,
        feather: Float,
    ) {
        if (radius <= 1f) {
            drawRect(Color.Black, alpha = IRIS_DARK)
            return
        }
        val lit = ((radius - feather) / radius).coerceIn(0f, 0.999f)
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                lit to Color.Transparent,
                1f to Color.Black.copy(alpha = IRIS_DARK),
                center = center,
                radius = radius,
                tileMode = TileMode.Clamp,
            ),
        )
    }

    private fun DrawScope.drawKeyGlow(
        key: Rect,
        glow: Float,
    ) {
        val margin = 44f * density
        val area = key.inflate(margin)
        drawOval(
            Brush.radialGradient(
                0f to Color(0xFFFFDCAA).copy(alpha = 0.8f * glow),
                0.55f to Color(0xFFFFB06A).copy(alpha = 0.3f * glow),
                1f to Color.Transparent,
                center = area.center,
                radius = max(area.width, area.height) / 2f,
            ),
            topLeft = area.topLeft,
            size = area.size,
            blendMode = BlendMode.Screen,
        )
    }

    // --------------------------------------------------------------- 玻璃舱

    private fun DrawScope.drawGlass(
        t: Float,
        u: Float,
    ) {
        val returning = u >= 0f
        val recede = if (returning) 1f - handoffSegment(u, 60f, 400f) else handoffSegment(t, 0f, 300f)
        drawRect(Color.Black, alpha = 0.85f * recede)
        val glass = if (returning) 1f - handoffSegment(u, 60f, 400f) else handoffSegment(t, 0f, 240f)
        val shown = if (returning) 1f - handoffSegment(u, 400f, 120f, HandoffLinear) else handoffSegment(t, 0f, 120f)
        val wobble =
            if (returning && u > 60f) {
                0.05f * sin((u - 60f) / 95f * PI.toFloat()) * exp(-(u - 60f) / 150f)
            } else {
                0f
            }
        drawGlassKey(glass, shown, wobble)
    }

    /** The key as a glass bar, [glass] of the way there; the same drawing the player takes over from. */
    private fun DrawScope.drawGlassKey(
        glass: Float,
        shown: Float,
        wobble: Float,
    ) {
        if (shown <= 0f) return
        val key = launch.keyOnScreen(density).translate(-origin)
        val bar = launch.glassBarOnScreen(density).translate(-origin)
        val rect =
            Rect(
                mixFloat(key.left, bar.left, glass),
                key.top,
                mixFloat(key.right, bar.right, glass),
                key.bottom,
            )
        val corner = launch.keyCorner(density)
        val box =
            HandoffBox.of(rect, corner).let {
                it.copy(width = it.width * (1f - 0.4f * wobble), height = it.height * (1f + wobble))
            }
        val tint = launch.key?.tint
        val ink = launch.key?.ink ?: Color.White
        val clear = launch.key?.glass ?: (tint == null)
        inBox(box) {
            clipPath(box.localPath()) {
                val outline = box.localRoundRect()
                val topLeft = Offset(outline.left, outline.top)
                val area = Size(outline.width, outline.height)
                drawRect(Color(0xFF0B0809), alpha = shown * glass, topLeft = topLeft, size = area)
                if (clear) {
                    drawRect(
                        Color(0xFF121620),
                        alpha = shown * mixFloat(0.68f, 0.5f, glass),
                        topLeft = topLeft,
                        size = area,
                    )
                } else if (tint != null) {
                    drawRect(
                        Brush.linearGradient(
                            0f to lerp(tint, ink, 0.1f),
                            1f to tint,
                            start = topLeft,
                            end = Offset(outline.right, outline.bottom),
                        ),
                        alpha = shown * mixFloat(1f, 0.3f, glass),
                        topLeft = topLeft,
                        size = area,
                    )
                }
            }
        }
        drawGlassRim(box, shown * glass)
        val wellX = mixFloat(key.left + WELL_INSET_DP * density, rect.center.x, glass)
        drawPlayWell(Offset(wellX, rect.center.y), 16f * density, shown, glass, ink)
    }

    private fun DrawScope.drawPlayWell(
        center: Offset,
        radius: Float,
        alpha: Float,
        glass: Float,
        ink: Color,
    ) {
        val well = if (glass > 0.5f) Color.White.copy(alpha = 0.14f) else ink.copy(alpha = 0.16f)
        drawCircle(well, radius = radius, center = center, alpha = alpha)
        val icon = play ?: return
        val side = radius * 0.94f
        val iconTint = if (glass > 0.5f) Color.White else ink
        translate(center.x - side / 2f, center.y - side / 2f) {
            with(icon) { draw(Size(side, side), alpha, ColorFilter.tint(iconTint)) }
        }
    }

    // --------------------------------------------------------------- 推近

    private fun DrawScope.drawPushIn(
        t: Float,
        u: Float,
    ) {
        val returning = u >= 0f
        val dim = if (returning) 0.9f * (1f - handoffSegment(u, 100f, 360f)) else 0.9f * handoffSegment(t, 0f, 200f)
        drawRect(Color.Black, alpha = dim)
        val zoom = pushZoom(this.size)
        val z = if (returning) 1f - handoffSegment(u, 60f, 400f) else handoffSegment(t, 0f, 340f)
        val s = mixFloat(1f, zoom.scale, z)
        val hero = hero()
        val pivot = Offset(zoom.origin.pivotFractionX * this.size.width, zoom.origin.pivotFractionY * this.size.height)
        val center = pivot + (hero.center - pivot) * s
        val copy = if (returning) 1f - handoffSegment(u, 300f, 240f) else handoffSegment(t, 0f, 160f)
        drawArtwork(art, HandoffBox(center, hero.width * s, hero.height * s), copy)
    }

    private class Zoom(
        val scale: Float,
        val origin: TransformOrigin,
    )

    /** The zoom that maps the artwork's centre to the screen's and makes it cover the screen. */
    private fun pushZoom(size: Size): Zoom {
        val hero = hero()
        val scale = launch.pushScale()
        if (size.width <= 0f || size.height <= 0f || scale <= 1.001f) return Zoom(1f, TransformOrigin.Center)
        val pivot = (hero.center * scale - Offset(size.width / 2f, size.height / 2f)) / (scale - 1f)
        return Zoom(scale, TransformOrigin(pivot.x / size.width, pivot.y / size.height))
    }

    // --------------------------------------------------------------- 潮汐

    private fun DrawScope.drawTide(
        t: Float,
        u: Float,
    ) {
        val dim = if (u >= 0f) 0.97f * (1f - handoffSegment(u, 0f, 520f)) else 0.97f * handoffSegment(t, 60f, 520f)
        drawRect(Color.Black, alpha = dim)
    }

    // --------------------------------------------------------------- 虚焦

    private fun DrawScope.drawDefocus(
        t: Float,
        u: Float,
        heroLayer: GraphicsLayer,
        fieldLayer: GraphicsLayer,
    ) {
        val returning = u >= 0f
        val defocus = if (returning) 1f - handoffSegment(u, 60f, 480f) else handoffSegment(t, 0f, 320f)
        val copy = if (returning) 1f - handoffSegment(u, 420f, 220f) else 1f
        val hero = HandoffBox.of(hero()).scaled(1f + 0.18f * defocus)
        heroLayer.renderEffect = blurs.of(26f * density * defocus)
        heroLayer.record(IntSize(this.size.width.toInt(), this.size.height.toInt())) {
            drawArtwork(art, hero, 1f, colorFilter = saturations.of(1f + 0.3f * defocus))
        }
        heroLayer.alpha = copy
        drawLayer(heroLayer)

        val fieldAlpha = if (returning) 1f - handoffSegment(u, 60f, 480f) else handoffSegment(t, 0f, 300f)
        if (fieldAlpha <= 0f) return
        val painter = if (returning) field ?: leaveField else leaveField
        val screen = HandoffBox(Offset(this.size.width / 2f, this.size.height / 2f), this.size.width, this.size.height)
        fieldLayer.renderEffect = blurs.of(FIELD_BLUR_DP * density)
        fieldLayer.record(IntSize(this.size.width.toInt(), this.size.height.toInt())) {
            drawArtwork(painter, screen.scaled(FIELD_OVERSCAN), 1f, colorFilter = saturations.of(FIELD_SATURATION))
        }
        fieldLayer.alpha = fieldAlpha
        drawLayer(fieldLayer)
    }

    // --------------------------------------------------------------- shared

    private fun DrawScope.farthestCorner(point: Offset): Float =
        maxOf(
            hypot(point.x, point.y),
            hypot(this.size.width - point.x, point.y),
            hypot(point.x, this.size.height - point.y),
            hypot(this.size.width - point.x, this.size.height - point.y),
        )
}

/** The 虚焦 field is richer than the artwork it comes from. */
private const val FIELD_SATURATION = 1.35f

private const val SETTLE_DAMPING = 0.85f

/** The leaving clock's value once the page is holding: past every set's last page-side step. */
private const val HELD = 10_000f
private const val IRIS_DARK = 0.97f
private const val TIDE_AMPLITUDE_DP = 20f
private const val WELL_INSET_DP = 29f
private const val FIELD_BLUR_DP = 18f
private const val FIELD_OVERSCAN = 1.12f
