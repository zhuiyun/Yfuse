package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 情境提示 — a new gesture explained once, where it can first be used, and never again.
 *
 * Modelled on Apple's TipKit: a tip shows only where its gesture applies, retires once it has been
 * on screen for [TIP_SEEN_MS] (or was answered with 知道了, or left with its page) or the gesture has
 * been used — whichever comes first — and no more than one tip is seen on any day, so a first run is
 * not a tour. A tip that flashed past because the controls under it went away has not been seen, and
 * may come back.
 *
 * One id per place: a swipe on a download does something else than a swipe on 观看记录, and seeing
 * one of those tips used to retire the other two unread.
 */
object Tips {
    /** 浮起菜单 on content posters. */
    const val LIFT = "tip.lift"

    /** 长按画面中间, the temporary speed boost. */
    const val PLAYER_CENTER_HOLD = "tip.player.centerHold"

    /** Scrubbing finer by moving up off the progress bar. */
    const val FINE_SCRUB = "tip.player.fineScrub"

    /** 双击左右 to seek, and taps after it adding up. */
    const val PLAYER_DOUBLE_TAP = "tip.player.doubleTap"

    /** 横滑 across the picture to scrub. */
    const val PLAYER_SWIPE_SEEK = "tip.player.swipeSeek"

    /** 竖滑 on either half for brightness and volume. */
    const val PLAYER_SIDE_DRAG = "tip.player.sideDrag"

    /** 没听清: holding ⟲10. */
    const val PLAYER_MISSED_LINE = "tip.player.missedLine"

    /** 点弹幕 for its menu. */
    const val PLAYER_DANMAKU_PICK = "tip.player.danmakuPick"

    /** 捏合 to fill the screen. */
    const val PLAYER_PINCH_FILL = "tip.player.pinchFill"

    /** Holding 弹幕 for its settings; a tap only switches it. */
    const val PLAYER_DANMAKU_KEY = "tip.player.danmakuKey"

    /** Swiping a download row for its actions. */
    const val SWIPE_ROW_DOWNLOADS = "tip.swipeRow.downloads"

    /** Swiping a 观看记录 row for its actions. */
    const val SWIPE_ROW_HISTORY = "tip.swipeRow.history"

    /** Swiping an episode in 管理进度 for its actions. */
    const val SWIPE_ROW_EPISODES = "tip.swipeRow.episodes"

    /** Pinching a grid to change its density. */
    const val PINCH_GRID = "tip.pinchGrid"

    /** Pulling a page down to send it back into its poster. */
    const val ZOOM_BACK = "tip.zoomBack"
}

/** Where tips remember themselves; [TipsState] owns the rules. */
interface TipsStore {
    /** Shown before, or its gesture already used: either way it is never shown again. */
    fun isRetired(id: String): Boolean

    fun retire(id: String)

    /** The ISO date the last tip was shown on, if any. */
    fun lastShownDay(): String?

    fun setLastShownDay(day: String)
}

@Stable
class TipsState(
    private val store: TipsStore,
    private val today: () -> String,
) {
    /** The tip on screen now; at most one. */
    var showing by mutableStateOf<String?>(null)
        private set

    /** Where [showing] is shown: one place, the one that claimed it. */
    private var holder by mutableStateOf<Any?>(null)

    /**
     * Whether [id] may appear now at [place]. A tip already showing keeps its place; nothing else
     * may appear while it is up — the same tip at another place included, which would show it
     * twice — nor on a day that has already had its tip seen.
     */
    fun claim(
        id: String,
        place: Any,
    ): Boolean {
        if (showing == id) return holder == place
        if (showing != null || store.isRetired(id)) return false
        if (store.lastShownDay() == today()) return false
        showing = id
        holder = place
        return true
    }

    /**
     * [id] has been on screen long enough to read, or was answered: it retires and takes the day's
     * one slot. Appearing is not enough — a tip under controls that hid a moment later was not read.
     */
    fun markSeen(id: String) {
        if (!store.isRetired(id)) store.retire(id)
        store.setLastShownDay(today())
    }

    /** Whether [id] is the tip on screen, shown at [place]. */
    fun isShowing(
        id: String,
        place: Any,
    ): Boolean = showing == id && holder == place

    /**
     * [place] left the screen. A tip it was showing goes with it and frees the slot. One in front
     * of the reader as the page went ([seen]) counts as seen, so it does not come back when the
     * page does; one hidden under controls that had already gone away was not read, and may.
     */
    fun release(
        id: String,
        place: Any,
        seen: Boolean = true,
    ) {
        if (!isShowing(id, place)) return
        if (seen) markSeen(id)
        dismiss(id)
    }

    /** The tip was closed, or timed out. */
    fun dismiss(id: String) {
        if (showing != id) return
        showing = null
        holder = null
    }

    /** The gesture a tip teaches was just used: it will never be shown, and leaves if it is up. */
    fun markUsed(id: String) {
        if (!store.isRetired(id)) store.retire(id)
        dismiss(id)
    }
}

/** The app's tips; null where there are none to show (previews, tests). */
val LocalTips = staticCompositionLocalOf<TipsState?> { null }

/** How long a tip stays before it retires on its own, before any accessibility adjustment. */
private const val TIP_MS = 6_000L

/** How long a tip has to be on screen to count as seen. */
internal const val TIP_SEEN_MS = 2_000L

/**
 * A tip bubble for [id], shown once [active] says its gesture is in reach — the posters it is
 * about are on screen, the player's controls are up. Place it where the gesture happens; it
 * asks for its own slot and gives it back.
 */
@Composable
fun ContextualTip(
    id: String,
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val tips = LocalTips.current ?: return
    val accessibility = LocalAccessibilityManager.current
    // This placement of the tip; the same id can sit on two pages at once, mid-transition.
    val place = remember { Any() }
    val shown = tips.isShowing(id, place)
    val latestActive by rememberUpdatedState(active)
    LaunchedEffect(active) {
        if (active) tips.claim(id, place)
    }
    // A page left before the tip timed out takes it along; it used to keep the slot for the rest
    // of the process, blocking every other tip and showing again on the way back.
    DisposableEffect(tips, id) {
        onDispose { tips.release(id, place, seen = latestActive) }
    }
    // Seen once it has stayed up long enough to read, not the moment it appears.
    val onScreen = shown && active
    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        delay(TIP_SEEN_MS)
        tips.markSeen(id)
    }
    LaunchedEffect(shown, accessibility) {
        if (!shown) return@LaunchedEffect
        val timeout =
            accessibility?.calculateRecommendedTimeoutMillis(
                TIP_MS,
                containsIcons = false,
                containsText = true,
                containsControls = true,
            ) ?: TIP_MS
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        tips.dismiss(id)
    }
    // 减少动画 and 静息 keep the tip's fade and drop its rise, as the handoff banner does.
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    AnimatedVisibility(
        visible = shown && latestActive,
        modifier = modifier,
        enter =
            if (still) {
                fadeIn(Motion.tween(Motion.REDUCED_FADE))
            } else {
                fadeIn(Motion.tween(Motion.STANDARD)) + slideInVertically(Motion.tween(Motion.STANDARD)) { it / 3 }
            },
        exit =
            if (still) {
                fadeOut(Motion.tween(Motion.QUICK))
            } else {
                fadeOut(Motion.tween(Motion.QUICK)) + slideOutVertically(Motion.tween(Motion.QUICK)) { it / 3 }
            },
    ) {
        val palette = LocalPalette.current
        Row(
            Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = Dimens.pageHorizontal)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .glass(shape = AppShapes.card, fill = palette.glassStrong, border = palette.border)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = AppTypography.body.medium,
                color = palette.text,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp),
            )
            // The press washes the word and its padding, not the 48dp slot around them.
            val dismissFocus = remember { TouchTargetFocusShape(AppShapes.control) }
            Text(
                "知道了",
                style = AppTypography.body.strong,
                color = LocalAccentColors.current.accent,
                modifier =
                    Modifier
                        .pressable(
                            focusShape = dismissFocus,
                            onClick = {
                                tips.markSeen(id)
                                tips.dismiss(id)
                            },
                        ).touchTarget(focus = dismissFocus)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
