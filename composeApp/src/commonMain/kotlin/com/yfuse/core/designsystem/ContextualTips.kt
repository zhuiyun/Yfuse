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
 * Modelled on Apple's TipKit: a tip shows only where its gesture applies, retires the moment it
 * has been shown or the gesture has been used — whichever comes first — and no more than one
 * tip appears on any day, so a first run is not a tour.
 */
object Tips {
    /** 浮起菜单 on content posters. */
    const val LIFT = "tip.lift"

    /** 长按画面中间, the temporary speed boost. */
    const val PLAYER_CENTER_HOLD = "tip.player.centerHold"

    /** Scrubbing finer by moving up off the progress bar. */
    const val FINE_SCRUB = "tip.player.fineScrub"

    /** Swiping up and down an upright 短剧 to change episode. */
    const val SHORT_DRAMA_SWIPE = "tip.player.shortDramaSwipe"

    /** Swiping a row for its actions. */
    const val SWIPE_ROW = "tip.swipeRow"

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
     * Whether [id] may appear now at [place], taking the day's one slot if so. A tip already
     * showing keeps its slot; nothing else may take one while it is up — the same tip at another
     * place included, which would show it twice.
     */
    fun claim(
        id: String,
        place: Any,
    ): Boolean {
        if (showing == id) return holder == place
        if (showing != null || store.isRetired(id)) return false
        val day = today()
        if (store.lastShownDay() == day) return false
        showing = id
        holder = place
        // Shown once is shown enough: it retires now, not when it is dismissed.
        store.retire(id)
        store.setLastShownDay(day)
        return true
    }

    /** Whether [id] is the tip on screen, shown at [place]. */
    fun isShowing(
        id: String,
        place: Any,
    ): Boolean = showing == id && holder == place

    /**
     * [place] left the screen. A tip it was showing goes with it and frees the slot; it is retired
     * already, so it does not come back when the page does.
     */
    fun release(
        id: String,
        place: Any,
    ) {
        if (isShowing(id, place)) dismiss(id)
    }

    /** The tip was read, closed, or timed out. */
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
        onDispose { tips.release(id, place) }
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
    AnimatedVisibility(
        visible = shown && latestActive,
        modifier = modifier,
        enter = fadeIn(Motion.tween(Motion.STANDARD)) + slideInVertically(Motion.tween(Motion.STANDARD)) { it / 3 },
        exit = fadeOut(Motion.tween(Motion.QUICK)) + slideOutVertically(Motion.tween(Motion.QUICK)) { it / 3 },
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
                        .pressable(focusShape = dismissFocus, onClick = { tips.dismiss(id) })
                        .touchTarget(focus = dismissFocus)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
