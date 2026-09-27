package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.glass
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 长按中间 — the playback speed while a press on the middle third of the picture is held.
 *
 * The two outer thirds keep 长按快进 / 快退, which runs along the timeline; see the note on
 * `HOLD_SEEK_TICK_MS` in PlayerControls for why that replaced a held 2× there. The middle
 * third, where a double tap plays and pauses, held nothing. Holding it now plays faster the way
 * B 站 and YouTube do: 2× to start, a sideways slide shifts between the gears, and letting go
 * restores whatever speed was set before. The boost is never remembered as the series' speed.
 */
internal val SPEED_BOOST_GEARS = listOf(1.5f, 2f, 3f)

/** The gear a hold starts in: 2×. */
internal const val SPEED_BOOST_DEFAULT_GEAR = 1

/** How far the finger slides sideways to reach the next gear. */
internal val SpeedBoostGearStep: Dp = 44.dp

/**
 * How far past a mark, in steps, a finger heading back towards the middle has to go before the
 * gear drops. A finger resting on a mark jitters by a pixel or two, and without this the gear —
 * and the tick that announces it — would rattle between the two sides.
 */
private const val SPEED_BOOST_HYSTERESIS = 0.2f

/**
 * The gear for a finger [dragX] pixels to the right of where the hold began, given the gear it
 * is in now. Every [stepPx] outwards is one gear; the middle gear keeps a full step either side,
 * so the drift of a finger that is only holding never changes speed.
 *
 * Right is faster on every layout direction: the gesture is about the finger, not about reading
 * order.
 */
internal fun speedBoostGearFor(
    dragX: Float,
    stepPx: Float,
    current: Int,
): Int {
    if (!dragX.isFinite() || !stepPx.isFinite() || stepPx <= 0f) return current
    val steps = dragX / stepPx
    val offset = current - SPEED_BOOST_DEFAULT_GEAR
    val reached = steps.toInt()
    val next =
        if (abs(reached) >= abs(offset)) {
            // Outwards, or across to the other side: the gear follows as soon as a mark is passed.
            reached
        } else {
            // Back towards the middle: only once clear of the mark it came in over.
            (steps + offset.sign * SPEED_BOOST_HYSTERESIS).toInt()
        }
    return (SPEED_BOOST_DEFAULT_GEAR + next).coerceIn(0, SPEED_BOOST_GEARS.lastIndex)
}

/** "1.5×", "2×", "3×" — no trailing ".0" on a whole gear. */
internal fun speedBoostLabel(speed: Float): String {
    val tenths = (speed * 10f).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10}.${tenths % 10}×"
}

/** Why a hold on the middle third does not speed playback up. */
internal enum class SpeedBoostRefusal(
    /** What the gesture HUD says; null where saying nothing is the right answer. */
    val message: String?,
) {
    /** A panel is up; the press belongs to closing it, not to the film. */
    PanelOpen(null),

    /** A guest whose room is driven by its host — the same answer as every other timeline gesture. */
    WatchGuest("房主控制播放"),

    /** The host of a room: everyone follows the host's rate, and the room keeps its own. */
    WatchRoom("一起看时不能临时倍速"),

    /** The picture is on another screen; the rate would change only the silent local copy. */
    Casting("投屏时不能临时倍速"),

    /** Nothing to play through: still loading, a live stream, already at its end, or failed. */
    NothingToPlay(null),
}

internal fun speedBoostRefusal(
    panelOpen: Boolean,
    watchGuest: Boolean,
    watchRoom: Boolean,
    casting: Boolean,
    durationMs: Long,
    finished: Boolean,
): SpeedBoostRefusal? =
    when {
        panelOpen -> SpeedBoostRefusal.PanelOpen
        watchGuest -> SpeedBoostRefusal.WatchGuest
        watchRoom -> SpeedBoostRefusal.WatchRoom
        casting -> SpeedBoostRefusal.Casting
        durationMs <= 0L || finished -> SpeedBoostRefusal.NothingToPlay
        else -> null
    }

/**
 * The pill that stays up for as long as the boost does: the current gear, and the other two
 * dimmed beside it so the slide has somewhere visible to go.
 *
 * The controls hide when the hold starts — the point of holding is to watch — so this sits
 * where the title bar would be, clear of the subtitles.
 */
@Composable
internal fun SpeedBoostPill(
    gear: Int?,
    modifier: Modifier = Modifier,
) {
    // The pill leaves showing the gear it was released in, not an empty shell. A plain holder,
    // not state: it only remembers, it never needs to recompose anything.
    val lastGear = remember { IntArray(1) { SPEED_BOOST_DEFAULT_GEAR } }
    if (gear != null) lastGear[0] = gear
    val shown = gear ?: lastGear[0]
    ChromeVisibility(visible = gear != null, edge = ChromeEdge.Top, modifier = modifier) {
        val current = SPEED_BOOST_GEARS[shown.coerceIn(0, SPEED_BOOST_GEARS.lastIndex)]
        Row(
            Modifier
                .clearAndSetSemantics {
                    contentDescription = "${speedBoostLabel(current)} 倍速播放，左右滑动换挡，松手恢复"
                    liveRegion = LiveRegionMode.Polite
                }.glass(
                    shape = AppShapes.pill,
                    fill = Color.Black.copy(alpha = 0.56f),
                    border = Color.White.copy(alpha = 0.24f),
                ).padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Forward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            SPEED_BOOST_GEARS.forEachIndexed { index, speed ->
                Text(
                    speedBoostLabel(speed),
                    style = if (index == shown) AppTypography.body.strong else AppTypography.caption.regular,
                    color = if (index == shown) Color.White else Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }
}
