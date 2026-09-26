package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 浮起菜单 — what a long press on a content poster offers.
 *
 * The press lifts the poster out of its grid into a preview card, the page dims, and the
 * actions appear under the card. The finger that pressed can keep going: slide onto a row and
 * let go to run it, or onto the card to open the title. Letting go without moving leaves the
 * menu up to be tapped. It replaces the per-screen quick-action dialogs, which asked for a
 * press, a lift, a second press and a close for every one of these actions.
 *
 * A row either leaves the page — it opens something, and the lift fades so the next screen can
 * arrive — or changes the title in place, in which case the card settles back into the poster
 * first and the change lands where the person is looking. [leavesPage] says which.
 */
@Immutable
class LiftMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    /** A short second line: "剩余 42 分钟". */
    val detail: String? = null,
    val destructive: Boolean = false,
    val leavesPage: Boolean = false,
    val onSelect: () -> Unit,
)

@Immutable
class LiftMenu(
    val title: String,
    /** "2025 · 1 时 48 分 · ★ 8.4" — whatever the poster knows; null leaves the line out. */
    val meta: String? = null,
    /** The poster's own artwork, so the card leaves the grid showing what was pressed. */
    val artworkUrls: List<String> = emptyList(),
    /** Landscape art the card settles into once lifted; the poster art stays when there is none. */
    val backdropUrls: List<String> = emptyList(),
    /** 0..1 watched, drawn along the card's foot; null draws nothing. */
    val progress: Float? = null,
    val progressLabel: String? = null,
    /** Releasing on the card, or tapping it: open the title. Null when there is nothing to open. */
    val onOpen: (() -> Unit)? = null,
    /** Rows in groups; a hairline separates one group from the next. Empty groups are dropped. */
    sections: List<List<LiftMenuAction>>,
) {
    val sections: List<List<LiftMenuAction>> = sections.filter { it.isNotEmpty() }
    val actions: List<LiftMenuAction> = this.sections.flatten()

    /** This menu, starting from [urls] when it names no artwork of its own — the poster fills it in. */
    internal fun withArtwork(urls: List<String>): LiftMenu =
        if (artworkUrls.isNotEmpty() || urls.isEmpty()) {
            this
        } else {
            LiftMenu(
                title = title,
                meta = meta,
                artworkUrls = urls,
                backdropUrls = backdropUrls,
                progress = progress,
                progressLabel = progressLabel,
                onOpen = onOpen,
                sections = sections,
            )
        }
}

// ------------------------------------------------------------------ geometry
//
// Everything below is plain arithmetic on root-window pixels, so where the card lands and which
// row a finger is over can be tested without a screen.

/** The preview card never grows past this on a wide window; a phone gets its width less margins. */
internal val LiftCardMaxWidth = 320.dp

/** Card height over width: the landscape art the card settles into. */
internal const val LIFT_CARD_ASPECT = 0.62f

internal val LiftMenuRowHeight = MinTouchTarget
internal val LiftMenuSeparatorHeight = 9.dp
internal val LiftMenuPadding = 6.dp
internal val LiftGap = 10.dp
internal val LiftMargin = 16.dp

/** The fewest rows a menu is squeezed to before the card itself gives up height instead. */
private const val LIFT_MENU_MIN_ROWS = 2

/** The card's words arrive over the last stretch of the lift, once it is nearly card-shaped. */
private const val LIFT_TEXT_FROM = 0.55f
private const val LIFT_TEXT_SPAN = 0.4f

/** 0 until the card is [LIFT_TEXT_FROM] of the way there, then up over [LIFT_TEXT_SPAN]. */
internal fun liftTextAlpha(fraction: Float): Float = ((fraction - LIFT_TEXT_FROM) / LIFT_TEXT_SPAN).coerceIn(0f, 1f)

/** Where the card and the menu sit once lifted, in root pixels. */
@Immutable
internal data class LiftPlacement(
    val card: Rect,
    val menu: Rect,
    /** True when the rows are taller than the room the menu got, so the menu scrolls. */
    val menuScrolls: Boolean,
)

/** Height of a menu's rows and separators, padding included. */
internal fun liftMenuContentHeight(
    sectionSizes: List<Int>,
    rowHeight: Float,
    separatorHeight: Float,
    padding: Float,
): Float {
    val sections = sectionSizes.filter { it > 0 }
    if (sections.isEmpty()) return 0f
    return padding * 2f + sections.sum() * rowHeight + (sections.size - 1) * separatorHeight
}

/**
 * Card above menu, both near the poster that was pressed: the card is centred on the poster
 * and the pair slides only as far as it must to stay inside [bounds]. A poster near the foot of
 * the screen therefore ends up with the menu right under the finger that pressed it.
 *
 * When the column is taller than the window and there is width to spare — a phone on its side,
 * a tablet — card and menu stand side by side instead. Only when neither fits does the menu
 * scroll, and only past [LIFT_MENU_MIN_ROWS] rows does the card give up height.
 */
internal fun placeLift(
    source: Rect,
    bounds: Rect,
    card: Size,
    menuWidth: Float,
    menuHeight: Float,
    gap: Float,
    rowHeight: Float,
): LiftPlacement {
    val stackedHeight = card.height + gap + menuHeight
    val sideWidth = card.width + gap + menuWidth
    val sideBySide = stackedHeight > bounds.height && sideWidth <= bounds.width
    return if (sideBySide) {
        val left = clampStart(source.center.x - card.width / 2f, bounds.left, bounds.right - sideWidth)
        val cardTop = clampStart(source.center.y - card.height / 2f, bounds.top, bounds.bottom - card.height)
        val menuShown = minOf(menuHeight, bounds.height)
        val menuTop = clampStart(cardTop, bounds.top, bounds.bottom - menuShown)
        val menuLeft = left + card.width + gap
        LiftPlacement(
            card = Rect(left, cardTop, left + card.width, cardTop + card.height),
            menu = Rect(menuLeft, menuTop, menuLeft + menuWidth, menuTop + menuShown),
            menuScrolls = menuShown < menuHeight,
        )
    } else {
        val minimumMenu = minOf(menuHeight, rowHeight * LIFT_MENU_MIN_ROWS)
        val cardHeight = minOf(card.height, (bounds.height - gap - minimumMenu).coerceAtLeast(0f))
        val menuShown = minOf(menuHeight, (bounds.height - cardHeight - gap).coerceAtLeast(0f))
        val columnHeight = cardHeight + gap + menuShown
        val width = maxOf(card.width, menuWidth)
        val columnLeft = clampStart(source.center.x - width / 2f, bounds.left, bounds.right - width)
        val top = clampStart(source.center.y - cardHeight / 2f, bounds.top, bounds.bottom - columnHeight)
        val cardLeft = columnLeft + (width - card.width) / 2f
        val menuLeft = columnLeft + (width - menuWidth) / 2f
        val menuTop = top + cardHeight + gap
        LiftPlacement(
            card = Rect(cardLeft, top, cardLeft + card.width, top + cardHeight),
            menu = Rect(menuLeft, menuTop, menuLeft + menuWidth, menuTop + menuShown),
            menuScrolls = menuShown < menuHeight,
        )
    }
}

/** [value] kept between [low] and [high]; [low] wins when the range is empty. Never throws. */
private fun clampStart(
    value: Float,
    low: Float,
    high: Float,
): Float = value.coerceAtMost(high).coerceAtLeast(low)

/** What a finger over the lifted menu is pointing at. */
internal sealed interface LiftHit {
    data object None : LiftHit

    data object Card : LiftHit

    /** Index into [LiftMenu.actions]. */
    data class Row(
        val index: Int,
    ) : LiftHit
}

/**
 * The row or card under [point]. Rows are laid out top-down exactly as the host draws them:
 * [padding], then each section's rows with a [separatorHeight] between sections, all moved up
 * by [scroll]. Padding and separators hit nothing, so a finger crossing a hairline does not
 * tick for a row it has not reached.
 */
internal fun liftHitAt(
    point: Offset,
    placement: LiftPlacement,
    sectionSizes: List<Int>,
    rowHeight: Float,
    separatorHeight: Float,
    padding: Float,
    scroll: Float = 0f,
): LiftHit {
    if (placement.menu.contains(point)) {
        var y = placement.menu.top + padding - scroll
        var index = 0
        sectionSizes.filter { it > 0 }.forEachIndexed { section, count ->
            if (section > 0) y += separatorHeight
            repeat(count) {
                if (point.y >= y && point.y < y + rowHeight) return LiftHit.Row(index)
                y += rowHeight
                index++
            }
        }
        return LiftHit.None
    }
    return if (placement.card.contains(point)) LiftHit.Card else LiftHit.None
}

// ---------------------------------------------------------------------- state

/**
 * The one lifted poster, if any. Provided once at the root of the app ([LocalLiftMenu]) and
 * drawn by [LiftMenuHost], above the tab bar and below the dialog windows.
 */
@Stable
class LiftMenuState {
    internal var session by mutableStateOf<LiftSession?>(null)
        private set

    val isOpen: Boolean get() = session != null

    internal fun lift(
        menu: LiftMenu,
        source: Rect,
        finger: Offset?,
        onOpen: (() -> Unit)?,
        onSettled: () -> Unit,
    ): LiftSession {
        session?.abandon()
        return LiftSession(menu, source, finger, onOpen ?: menu.onOpen, onSettled) { finished ->
            if (session === finished) session = null
        }.also { session = it }
    }
}

/** How a lift leaves: back into its poster, or away with the page it opened. */
internal enum class LiftExit { None, SettleBack, FadeAway }

@Stable
internal class LiftSession(
    val menu: LiftMenu,
    /** The poster's bounds when it was pressed, in root pixels. */
    val source: Rect,
    finger: Offset?,
    private val onOpenTitle: (() -> Unit)?,
    private val onSettled: () -> Unit,
    private val onFinished: (LiftSession) -> Unit,
) {
    /** Whether the finger that lifted the poster is still down and steering. */
    var holding by mutableStateOf(finger != null)
        private set

    var hot by mutableStateOf<LiftHit>(LiftHit.None)
        private set

    var exit by mutableStateOf(LiftExit.None)
        private set

    /** Whether the poster is still in the page for the card to settle back into. */
    var sourceAttached = true
        private set

    val canOpen: Boolean get() = onOpenTitle != null

    /**
     * Written by the host once it has laid the card and menu out; read to hit-test the finger. A
     * menu cannot scroll while the finger that lifted it is still down, so the rows sit where the
     * placement says.
     */
    var placement: LiftPlacement? = null

    /** Row geometry in pixels, written by the host alongside [placement]. */
    var rowHeight = 0f
    var separatorHeight = 0f
    var padding = 0f

    private val origin = finger
    private var steering = false
    private var pending: (() -> Unit)? = null

    /** Replaced by another lift before it finished; its finger may still be down, but it runs nothing. */
    private var abandoned = false

    private val sectionSizes = menu.sections.map { it.size }

    /**
     * The finger moved. Nothing is hit until it has travelled [slop] from where the lift began —
     * the card lands under a still finger, and a tremor must not turn letting go into 打开.
     * Returns true when the finger arrived on something new, which is what earns a tick.
     */
    fun steer(
        finger: Offset,
        slop: Float,
    ): Boolean {
        if (abandoned || !holding || exit != LiftExit.None) return false
        if (!steering) {
            val start = origin ?: finger
            if ((finger - start).getDistance() <= slop) return false
            steering = true
        }
        val laid = placement ?: return false
        val next = liftHitAt(finger, laid, sectionSizes, rowHeight, separatorHeight, padding)
        if (next == hot) return false
        hot = next
        return next != LiftHit.None
    }

    /** The lifting finger came up: run what it was over, or stay up to be tapped. */
    fun release() {
        if (!holding || abandoned) return
        holding = false
        when (val target = hot) {
            LiftHit.Card -> open()
            is LiftHit.Row -> menu.actions.getOrNull(target.index)?.let(::select)
            LiftHit.None -> Unit
        }
    }

    /** The stream was taken away mid-hold; what is on screen stays, to be tapped. */
    fun stopHolding() {
        if (!holding) return
        holding = false
        hot = LiftHit.None
    }

    fun select(action: LiftMenuAction) {
        if (abandoned || exit != LiftExit.None) return
        val index = menu.actions.indexOf(action)
        if (index >= 0) hot = LiftHit.Row(index)
        if (action.leavesPage) {
            exit = LiftExit.FadeAway
            action.onSelect()
        } else {
            exit = if (sourceAttached) LiftExit.SettleBack else LiftExit.FadeAway
            pending = action.onSelect
        }
    }

    fun open() {
        val open = onOpenTitle ?: return dismiss()
        if (abandoned || exit != LiftExit.None) return
        hot = LiftHit.Card
        exit = LiftExit.FadeAway
        open()
    }

    fun dismiss() {
        if (exit != LiftExit.None) return
        holding = false
        exit = if (sourceAttached) LiftExit.SettleBack else LiftExit.FadeAway
    }

    /** The pressed poster left the page — a refresh, a removal. Nothing to settle back into now. */
    fun sourceDetached() {
        sourceAttached = false
        holding = false
    }

    /** Called by the host once the exit has played: the poster shows again, then the action runs. */
    fun finish() {
        onSettled()
        onFinished(this)
        pending?.also { pending = null }?.invoke()
    }

    /** Another poster was lifted before this one finished leaving. */
    fun abandon() {
        abandoned = true
        holding = false
        pending = null
        onSettled()
        onFinished(this)
    }
}

/** The app's lift host; null where there is none (the television, previews), and posters then keep no long press. */
val LocalLiftMenu = staticCompositionLocalOf<LiftMenuState?> { null }
