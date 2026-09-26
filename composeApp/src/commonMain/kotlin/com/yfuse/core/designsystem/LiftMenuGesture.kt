package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a still press waits before it sinks further, hinting that holding will do something. */
private const val LIFT_SINK_DELAY_MS = 150L

/** How far the sink goes on top of [pressable]'s own 0.97: together about 0.93. */
private const val LIFT_SINK_SCALE = 0.96f

/**
 * Where a lift starts from when that is not the node carrying the gesture. A captioned poster
 * takes the press on the whole tile, caption and all, but it is the artwork that lifts.
 */
@Stable
class LiftAnchor {
    internal var coordinates: LayoutCoordinates? = null
}

/** Marks this node as the artwork a [liftable] tile lifts out of. */
fun Modifier.liftAnchor(anchor: LiftAnchor?): Modifier =
    if (anchor == null) this else onPlaced { anchor.coordinates = it }

/**
 * The long press of a content poster: lifts it into the app's [LiftMenuHost] and steers the menu
 * with the same finger until it lets go. See [LiftMenu] for what the person gets.
 *
 * Chain it **before** [pressable], which must then have no `onLongClick` of its own:
 *
 * ```
 * Modifier.liftable(menu = { titleMenu(item) }).pressable(onClick = open)
 * ```
 *
 * Outermost is what makes the slide possible. `combinedClickable` stops reporting the pointer
 * once its long press has fired, so the menu could only ever be tapped; this watches the stream
 * from outside the click and, once the press has lifted, takes every later event on the way
 * *down* — before the click underneath, and before any list the poster sits in — so neither
 * clicks nor scrolls when the finger comes up. Until the long press fires it only listens: a
 * scroll that starts first consumes the stream and the lift never happens.
 *
 * Also reached without a finger: 菜单键 or Shift+F10 on a keyboard, and the long-press action of a
 * screen reader, which opens the same menu to be moved through. A screen reader also gets every
 * row directly, as custom actions on the poster.
 *
 * With no [LocalLiftMenu] (the television, previews) this adds nothing.
 *
 * @param menu built when the press lifts — and when a screen reader asks for the poster's
 *   actions — rather than once per tile on every composition.
 * @param anchor the artwork, when it is a child of this node rather than the node itself.
 * @param onOpen what releasing on the card does, when the poster already knows how it opens —
 *   the shared-element entrance to 详情, say. Falls back to [LiftMenu.onOpen].
 */
@Composable
fun Modifier.liftable(
    menu: (() -> LiftMenu)?,
    anchor: LiftAnchor? = null,
    onOpen: (() -> Unit)? = null,
): Modifier {
    val host = LocalLiftMenu.current
    if (menu == null || host == null) return this
    val haptics = LocalHaptics.current
    val tips = LocalTips.current
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    // The detector below lives as long as the host; it reads the newest of each of these.
    val latestMenu by rememberUpdatedState(menu)
    val latestAnchor by rememberUpdatedState(anchor)
    val latestOpen by rememberUpdatedState(onOpen)
    val latestStill by rememberUpdatedState(still)
    val latestHaptics by rememberUpdatedState(haptics)
    val own = remember { LiftAnchor() }
    val scope = rememberCoroutineScope()
    val sink = remember { Animatable(1f) }
    var lifted by remember { mutableStateOf(false) }
    // The lift this tile is in, if any. A plain holder: nothing is drawn from it.
    val current = remember { arrayOfNulls<LiftSession>(1) }
    DisposableEffect(Unit) {
        onDispose { current[0]?.sourceDetached() }
    }

    fun lift(finger: Offset?): LiftSession? {
        val self = own.coordinates?.takeIf { it.isAttached } ?: return null
        val art = latestAnchor?.coordinates?.takeIf { it.isAttached } ?: self
        val built = latestMenu()
        val session =
            host.lift(
                menu = built,
                source = art.boundsInRoot(),
                finger = finger,
                onOpen = latestOpen,
                onSettled = {
                    lifted = false
                    current[0] = null
                },
            )
        current[0] = session
        // A poster hands itself over to the card; a button keeps its place beside its menu.
        lifted = !built.anchored
        latestHaptics.play(HapticSignal.LongPress)
        tips?.markUsed(Tips.LIFT)
        return session
    }

    return this
        .onPlaced { own.coordinates = it }
        .onKeyEvent { event ->
            val asked =
                event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed))
            asked && lift(finger = null) != null
        }.semantics {
            onLongClick(label = "更多操作") { lift(finger = null) != null }
            customActions = latestMenu().actions.accessibilityActions()
        }.pointerInput(host) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val sinking =
                    if (latestStill) {
                        null
                    } else {
                        scope.launch {
                            delay(LIFT_SINK_DELAY_MS)
                            sink.animateTo(LIFT_SINK_SCALE, Motion.tween(Motion.STANDARD))
                        }
                    }
                val pressed = awaitLongPressOrCancellation(down.id)
                sinking?.cancel()
                if (pressed == null) {
                    scope.launch { sink.animateTo(1f, Motion.pressSpec(pressed = false, reduceMotion = latestStill)) }
                    return@awaitEachGesture
                }
                scope.launch { sink.snapTo(1f) }
                val self = own.coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val session = lift(finger = self.localToRoot(pressed.position)) ?: return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                var pointer = pressed.id
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change =
                            event.changes.firstOrNull { it.id == pointer }
                                ?: event.changes.firstOrNull { it.pressed }
                                ?: break
                        pointer = change.id
                        val at = self.takeIf { it.isAttached }?.localToRoot(change.position) ?: break
                        if (!change.pressed) {
                            session.steer(at, slop)
                            session.release()
                            break
                        }
                        if (session.steer(at, slop)) latestHaptics.play(HapticSignal.Tick)
                    }
                } finally {
                    // A stream taken away mid-hold — the tile left, the window lost focus — leaves the
                    // menu up to be tapped rather than running whatever the finger happened to be on.
                    session.stopHolding()
                }
            }
        }.graphicsLayer {
            val scale = sink.value
            scaleX = scale
            scaleY = scale
            // The card is the poster while it is up; the grid keeps its place empty until it settles back.
            alpha = if (lifted) 0f else 1f
        }
}
