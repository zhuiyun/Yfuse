package com.yfuse.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The key the user pressed to start playback, as the transition draws it.
 *
 * [tint] is the key's own colour where it has one (the detail page's artwork-coloured key);
 * [glass] marks a translucent key over artwork (the library and home heroes).
 */
@Immutable
internal data class HandoffKey(
    val boundsOnScreen: Rect,
    val corner: Float,
    val tint: Color?,
    /** The key's content colour, chosen against [tint] for contrast. */
    val ink: Color?,
    val glass: Boolean,
)

/** One player launch, as both windows see it. Geometry is in the page window's screen pixels. */
@Immutable
internal class HandoffLaunch(
    val style: PlayerTransitionStyle,
    val startedAt: TimeMark,
    val screen: ScreenGeometry,
    /** The artwork's patch of the screen. */
    val hero: Rect,
    val urls: List<String>,
    /** The play key, when the launch came from one within the last few seconds. */
    val key: HandoffKey?,
) {
    fun elapsedMs(): Float = startedAt.elapsedNow().inWholeMicroseconds / 1000f

    /** Where the tide and the light start: the key when there is one, else low on the artwork. */
    val origin: Offset
        get() = key?.boundsOnScreen?.center ?: Offset(hero.center.x, hero.bottom - hero.height * 0.18f)
}

/** A key-shaped bar where there was no key to press (a launch from an episode row, say). */
internal fun HandoffLaunch.keyOnScreen(density: Float): Rect =
    key?.boundsOnScreen
        ?: Rect(
            hero.left + KEY_MARGIN_DP * density,
            hero.bottom - FALLBACK_KEY_LIFT_DP * density,
            hero.right - KEY_MARGIN_DP * density,
            hero.bottom - (FALLBACK_KEY_LIFT_DP - KEY_HEIGHT_DP) * density,
        )

internal fun HandoffLaunch.keyCorner(density: Float): Float = key?.corner ?: (KEY_CORNER_DP * density)

/** 玻璃舱's bar: the pressed key, stretched across the page to the detail page's own key width. */
internal fun HandoffLaunch.glassBarOnScreen(density: Float): Rect {
    val key = keyOnScreen(density)
    val margin = KEY_MARGIN_DP * density
    val width = screen.size.width
    return if (key.width >= width - 2f * margin - 1f) key else Rect(margin, key.top, width - margin, key.bottom)
}

/** 推近's zoom: enough to make the artwork cover the whole page, centred on it. */
internal fun HandoffLaunch.pushScale(): Float =
    if (hero.width <= 0f || hero.height <= 0f) {
        1f
    } else {
        maxOf(1f, screen.size.width / hero.width, screen.size.height / hero.height)
    }

private const val KEY_MARGIN_DP = 18f
private const val KEY_HEIGHT_DP = 52f
private const val KEY_CORNER_DP = 16f
private const val FALLBACK_KEY_LIFT_DP = 72f

internal enum class HandoffPhase {
    Idle,

    /** The page is playing, then holding, its half of the way out. */
    Leaving,

    /** The player drew its half of the way back; the page finishes from the same frame. */
    Returning,

    /** The player went away without coming back through the page (画中画, an error, a replacement). */
    Releasing,
}

/**
 * Process-local state shared by the page window and the player window.
 *
 * Both are in this process, so the page can hold its "left" state for exactly as long as the
 * player is up, and the player can tell the page the moment it is done drawing its half. The
 * page never resets on its own while [HandoffPhase.Leaving]: the player fades out over that very
 * frame, which is the only reason the seam cannot be seen.
 */
internal object PlayerHandoff {
    var phase by mutableStateOf(HandoffPhase.Idle)
        private set
    var launch by mutableStateOf<HandoffLaunch?>(null)
        private set

    /** A small copy of the paused frame, which 虚焦 dissolves the page back out of. */
    var returnFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    private var pressed: Pair<HandoffKey, TimeMark>? = null

    fun begin(next: HandoffLaunch) {
        launch = next
        returnFrame = null
        phase = HandoffPhase.Leaving
    }

    fun comeBack(
        from: HandoffLaunch,
        frame: ImageBitmap?,
    ) {
        if (launch !== from || phase != HandoffPhase.Leaving) return
        returnFrame = frame
        phase = HandoffPhase.Returning
    }

    /** Lets the page go back without the return choreography. A no-op once it is returning. */
    fun release(from: HandoffLaunch? = null) {
        if (from != null && launch !== from) return
        if (phase == HandoffPhase.Leaving) phase = HandoffPhase.Releasing
    }

    fun settle() {
        phase = HandoffPhase.Idle
        launch = null
        returnFrame = null
    }

    fun keyPressed(key: HandoffKey) {
        pressed = key to TimeSource.Monotonic.markNow()
    }

    fun recentKey(): HandoffKey? =
        pressed
            ?.takeIf { it.second.elapsedNow().inWholeMilliseconds <= KEY_MEMORY_MS }
            ?.first
}

private const val KEY_MEMORY_MS = 4_000L

/**
 * Marks a play key, so a transition can start from the key the user actually pressed.
 *
 * Only observes: the press is read on the initial pass and never consumed, so the key's own
 * `pressable` still gets the click.
 */
@Composable
internal fun Modifier.playerHandoffKey(
    corner: Dp,
    tint: Color? = null,
    ink: Color? = null,
    glass: Boolean = false,
): Modifier {
    val screen = rememberScreenGeometrySource()
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    val bounds = remember { arrayOfNulls<Rect>(1) }
    return this
        .onGloballyPositioned { bounds[0] = it.boundsInWindow().translate(screen.current().windowOffset) }
        .pointerInput(cornerPx, tint, ink, glass) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                bounds[0]?.let { PlayerHandoff.keyPressed(HandoffKey(it, cornerPx, tint, ink, glass)) }
            }
        }
}

/** Reads this window's view of the display at the moment it is asked — after layout, not at composition. */
internal fun interface ScreenGeometrySource {
    fun current(): ScreenGeometry
}

/** This window's view of the display: its rotation, its size, and where the window sits on it. */
@Composable
internal expect fun rememberScreenGeometrySource(): ScreenGeometrySource

/** RuntimeShader is API 33; below it 潮汐 moves the page as one piece. */
internal expect val supportsTideShader: Boolean

/**
 * The tide on the page: every pixel displaced by one damped crest travelling outward from
 * [origin] at [speed] px/ms. Leaving, a pixel fades to nothing after its crest; surfacing, it
 * rises from below into place as the crest reaches it. Null where [supportsTideShader] is false.
 */
internal expect fun tideRenderEffect(
    size: Size,
    origin: Offset,
    timeMs: Float,
    speed: Float,
    amplitude: Float,
    surfacing: Boolean,
): RenderEffect?
