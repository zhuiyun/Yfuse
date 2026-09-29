package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.CALM_DURATION_SCALE
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.calmMotion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Which edge a piece of player chrome belongs to, and therefore where it comes from.
 *
 * Chrome that is anchored to an edge should arrive from that edge — it is the difference
 * between a panel that slid in from where it lives and one that materialised on top of the
 * film. [None] is for surfaces that own the whole screen and have no edge of their own.
 */
internal enum class ChromeEdge { Top, Bottom, End, None }

/**
 * Entrance and exit for one piece of player chrome.
 *
 * The travel is deliberately a fraction of the surface rather than a fixed distance: the top
 * bar, the transport row and the episode drawer are wildly different heights, and a shared
 * dp would read as a nudge on one and a lurch on another. A sixth of the surface's own size
 * looks like the same gesture on all of them.
 *
 * Under 减弱动态效果 the movement goes and the crossfade stays: chrome appearing instantly
 * over a moving picture is harder to follow than chrome that fades, and a fade is not the
 * kind of motion that setting is there to suppress.
 *
 * Under 静息 the chrome moves the way its pages do: a shorter fade over a few dp from its edge,
 * both bars together rather than in sequence, and nothing scales.
 */
@Composable
internal fun ChromeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    edge: ChromeEdge = ChromeEdge.None,
    /**
     * True for a wrapper that covers the whole picture rather than a panel sitting on it.
     *
     * A surface with no edge of its own grows out of its own centre, which is right for a key or
     * a popup and wrong for something the size of the screen: locking, unlocking and every error
     * used to composite the entire frame into a scaled layer to move it by six percent — a full
     * screen of resampling per frame, for a gesture nobody reads as a scale at that size. These
     * fade and nothing more.
     */
    coversScreen: Boolean = false,
    /** Appear with no fade or scale — something already on screen in the same place hands over to it. */
    instantEnter: Boolean = false,
    /** Go with no fade or scale — something appearing in the same place takes over from it. */
    instantExit: Boolean = false,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val calm = !reduceMotion && calmMotion()
    // The two bars are one gesture in two halves: the title bar leads on the way in and
    // the transport follows a beat later; leaving, the transport goes first and the title
    // bar lingers. The offset is small enough to read as sequence, not as lag.
    val stagger = if (calm) 0 else Motion.PLAYER_CHROME_STAGGER
    val enterDelay = if (edge == ChromeEdge.Bottom) stagger else 0
    val exitDelay = if (edge == ChromeEdge.Top) stagger else 0
    val duration = if (calm) (Motion.STANDARD * CALM_DURATION_SCALE).roundToInt() else Motion.STANDARD
    val fade = tween<Float>(duration, delayMillis = enterDelay, easing = Motion.Curve)
    val slide = tween<IntOffset>(duration, delayMillis = enterDelay, easing = Motion.Curve)
    val fadeAway = tween<Float>(duration, delayMillis = exitDelay, easing = Motion.Curve)
    val slideAway = tween<IntOffset>(duration, delayMillis = exitDelay, easing = Motion.Curve)
    val calmTravelPx = with(LocalDensity.current) { CalmChromeTravel.roundToPx() }
    val travel: (Int) -> Int = { full -> if (calm) minOf(full / 6, calmTravelPx) else full / 6 }
    val moving = !reduceMotion

    val enter =
        when {
            !moving || coversScreen -> fadeIn(fade)
            edge == ChromeEdge.Top -> fadeIn(fade) + slideInVertically(slide) { -travel(it) }
            edge == ChromeEdge.Bottom -> fadeIn(fade) + slideInVertically(slide) { travel(it) }
            edge == ChromeEdge.End -> fadeIn(fade) + slideInHorizontally(slide) { travel(it) }
            calm -> fadeIn(fade)
            // Anchored inside its own full-screen box, so it grows out of the corner it sits in
            // rather than sliding the invisible dismiss catcher around with it.
            else ->
                fadeIn(fade) +
                    scaleIn(
                        tween(Motion.STANDARD, easing = Motion.Curve),
                        initialScale = 0.94f,
                        transformOrigin = TransformOrigin.Center,
                    )
        }
    val exit =
        when {
            !moving || coversScreen -> fadeOut(fadeAway)
            edge == ChromeEdge.Top -> fadeOut(fadeAway) + slideOutVertically(slideAway) { -travel(it) }
            edge == ChromeEdge.Bottom -> fadeOut(fadeAway) + slideOutVertically(slideAway) { travel(it) }
            edge == ChromeEdge.End -> fadeOut(fadeAway) + slideOutHorizontally(slideAway) { travel(it) }
            calm -> fadeOut(fadeAway)
            else ->
                fadeOut(fadeAway) +
                    scaleOut(
                        tween(Motion.STANDARD, easing = Motion.Curve),
                        targetScale = 0.94f,
                        transformOrigin = TransformOrigin.Center,
                    )
        }

    AnimatedVisibility(
        visible = visible,
        modifier =
            modifier.then(
                if (visible) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
            ),
        enter = if (instantEnter) EnterTransition.None else enter,
        exit = if (instantExit) ExitTransition.None else exit,
        content = content,
    )
}

/** 静息's chrome travel: the same few dp its pages move, enough to say which edge it belongs to. */
private val CalmChromeTravel = 8.dp

private class RetainedChrome<T>(
    var value: T?,
)

/** What a [PanelPresence] tells the panel inside it. */
@Immutable
internal class PanelPresenceSignal(
    val visible: Boolean,
    val onExited: () -> Unit,
)

/** Read by [PlayerPopupPanel] and [PlayerSidePanel] only; a dialog opened from a panel never sees it. */
internal val LocalPanelPresence = compositionLocalOf<PanelPresenceSignal?> { null }

/**
 * Keep-alive for a panel that animates itself — the popovers and the right-edge drawers.
 *
 * Wrapped in [ChromeContent] they moved twice: a 180 ms fade and slide around a panel already
 * playing its own entrance, and on the way out an invisible second exit after the panel's, which
 * held every touch for its length. Here nothing moves but the panel. When the owner closes it
 * from state — a pick applied, another panel opened, a tap on the picture — the last [value] stays
 * composed, the panel is told to leave the way it would for the person, and it is let go once it
 * has. A value that returns during the exit brings the panel back instead.
 */
@Composable
internal fun <T : Any> PanelPresence(
    value: T?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(T) -> Unit,
) {
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (value != null) retained = value }
    val shown = value ?: retained ?: return
    val visible = value != null
    // Content that never reports its exit is not kept for ever.
    if (!visible) {
        LaunchedEffect(Unit) {
            delay(PANEL_RELEASE_AFTER_MS)
            retained = null
        }
    }
    val signal = remember(visible) { PanelPresenceSignal(visible) { retained = null } }
    CompositionLocalProvider(LocalPanelPresence provides signal) {
        Box(modifier) { content(shown) }
    }
}

/** Longer than any panel's own exit. */
private const val PANEL_RELEASE_AFTER_MS = 1_000L

/** Retains only the small panel identity while ChromeVisibility completes its exit. */
@Composable
internal fun <T : Any> ChromeContent(
    value: T?,
    modifier: Modifier = Modifier,
    edge: ChromeEdge = ChromeEdge.None,
    /** See [ChromeVisibility]: a full-screen surface fades rather than scaling. */
    coversScreen: Boolean = false,
    content: @Composable BoxScope.(T) -> Unit,
) {
    val retained = remember { RetainedChrome(value) }
    SideEffect { if (value != null) retained.value = value }
    val shown = value ?: retained.value
    ChromeVisibility(
        visible = value != null,
        modifier = modifier,
        edge = edge,
        coversScreen = coversScreen,
    ) {
        Box(Modifier.fillMaxSize()) { if (shown != null) content(shown) }
    }
}
