package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
 * The rows are [ItemAction]s — the same ones a screen reader finds on the poster, the television
 * shows in its long-press panel and 详情's 更多 offers to a held finger.
 */
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
    /**
     * A button's menu rather than a poster's: no card, the menu pinned beside what was pressed,
     * which stays in place. Letting go back on the button does what tapping it does ([onOpen]).
     */
    val anchored: Boolean = false,
    /** Rows in groups; a hairline separates one group from the next. Empty groups are dropped. */
    sections: List<List<ItemAction>>,
    /** 按住拖看: frames the lifting finger can scrub through on the card; null leaves the card as it is. */
    val scrub: LiftScrub? = null,
) {
    val sections: List<List<ItemAction>> = sections.filter { it.isNotEmpty() }
    val actions: List<ItemAction> = this.sections.flatten()

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
                anchored = anchored,
                sections = sections,
                scrub = scrub,
            )
        }
}

/**
 * 按住拖看 — YouTube's thumbnail preview on a lifted card. While the finger that lifted it slides
 * sideways across the card, the card shows the frame for how far across it is: the left edge is
 * the start, the right edge the end. Letting go on the card still opens the title; sliding down
 * onto the rows still picks one.
 *
 * The frames usually arrive after the lift — an episode's are fetched when its card is held — so
 * [frameCount] is snapshot state, read afresh on every move: 0 while they load, or when there are
 * none, and the card simply stays its artwork.
 */
@Stable
interface LiftScrub {
    /** Frames to scrub through, in order; 0 while there are none (yet). */
    val frameCount: Int

    /**
     * The card has lifted under a finger: fetch the frames if they are not here yet. Not called
     * for a menu opened from a keyboard or a screen reader, nor when the menu is merely built — a
     * screen reader builds every poster's for its custom actions.
     */
    fun prepare() {}

    /** What the card reads out over frame [index]: its time into the title, "12:30". */
    fun label(index: Int): String

    /** Frame [index], filling [modifier]. */
    @Composable
    fun Frame(
        index: Int,
        modifier: Modifier,
    )
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

/** An anchored menu is as wide as its longest rows need, not as wide as a preview card. */
internal val LiftAnchoredMenuWidth = 248.dp

/**
 * An [LiftMenu.anchored] menu: under the button when it fits there, otherwise above it, lined
 * up with the button's outer edge — a button on the right half opens leftwards. The button
 * itself is the placement's card, so letting go back on it counts as tapping it. Scrolls when
 * neither side has the room.
 */
internal fun placeAnchoredMenu(
    source: Rect,
    bounds: Rect,
    menuWidth: Float,
    menuHeight: Float,
    gap: Float,
): LiftPlacement {
    val width = minOf(menuWidth, bounds.width).coerceAtLeast(0f)
    val below = (bounds.bottom - source.bottom - gap).coerceAtLeast(0f)
    val above = (source.top - gap - bounds.top).coerceAtLeast(0f)
    val downward = menuHeight <= below || below >= above
    val shown = minOf(menuHeight, if (downward) below else above)
    val top = if (downward) source.bottom + gap else source.top - gap - shown
    val preferred = if (source.center.x > bounds.center.x) source.right - width else source.left
    val left = clampStart(preferred, bounds.left, bounds.right - width)
    return LiftPlacement(
        card = source,
        menu = Rect(left, top, left + width, top + shown),
        menuScrolls = shown < menuHeight,
    )
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

/**
 * 按住拖看: the least finger travel between two frames. An episode has a frame every ten seconds or
 * so, some 270 across a 320 dp card: stepping through every one would buzz with ticks and flicker
 * between neighbours under a resting finger. At this spacing each change is a detent, at most 40
 * across a full-width card, spread evenly over the whole episode.
 */
internal val LiftScrubStep = 8.dp

/** How far across [card] [x] is: 0 at its left edge, 1 at its right, held there beyond them. */
internal fun liftScrubFraction(
    x: Float,
    card: Rect,
): Float = if (card.width > 0f) ((x - card.left) / card.width).coerceIn(0f, 1f) else 0f

/**
 * 按住拖看's dead zone: which way the finger has gone from [anchor] — where it came onto the card,
 * or last went up or down — once past [deadZone]. Sideways only when more across than down, the
 * way the browse pages tell a swipe from a scroll (8): [DragAxis.Horizontal] starts the scrub, a
 * finger heading for the rows is [DragAxis.Vertical], and a still one is not decided yet.
 */
internal fun liftScrubAxis(
    anchor: Offset,
    finger: Offset,
    deadZone: Float,
): DragAxis = resolveDragAxis(finger.x - anchor.x, finger.y - anchor.y, deadZone)

/** How many places a card [width] wide has for [frames] frames at least [step] apart; 0 for none. */
internal fun liftScrubStops(
    frames: Int,
    width: Float,
    step: Float,
): Int {
    if (frames <= 0 || width <= 0f) return 0
    if (step <= 0f) return frames
    return minOf(frames, (width / step).toInt().coerceAtLeast(1))
}

/** Which of [count] equal slices [fraction] falls in, the right edge in the last; -1 for none. */
internal fun liftScrubIndex(
    fraction: Float,
    count: Int,
): Int = if (count > 0) (fraction.coerceIn(0f, 1f) * count).toInt().coerceAtMost(count - 1) else -1

/**
 * The frame shown at [stop] of [stops]: spread evenly over [frames], so the first stop is the
 * first frame and the last stop the last — the left edge is the start, the right edge the end.
 */
internal fun liftScrubFrame(
    stop: Int,
    stops: Int,
    frames: Int,
): Int =
    when {
        stops <= 0 || frames <= 0 -> -1
        stops == 1 -> 0
        else -> (stop.coerceIn(0, stops - 1).toLong() * (frames - 1) / (stops - 1)).toInt()
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
        // Only a finger can scrub, so only a card a finger holds fetches its frames.
        if (finger != null) menu.scrub?.prepare()
        return LiftSession(menu, source, finger, onOpen ?: menu.onOpen, onSettled) { finished ->
            if (session === finished) session = null
        }.also { session = it }
    }
}

/**
 * How a lift leaves: back into its poster, away with the page it opened, or as that page — the
 * card growing into its hero (一镜到底, see [LiftExpansion]).
 */
internal enum class LiftExit { None, SettleBack, FadeAway, Expand }

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

    /** [LiftScrubStep] in pixels, written by the host alongside [placement]; 0 steps every frame. */
    var scrubStep = 0f

    /** 按住拖看: the frame of [LiftMenu.scrub] under the finger, or -1 while the card shows its artwork. */
    var scrubFrame by mutableIntStateOf(-1)
        private set

    /** How far across the card the scrubbing finger is, 0..1. Read while drawing; see [scrubFrame]. */
    var scrubFraction by mutableFloatStateOf(0f)
        private set

    private val origin = finger
    private var steering = false

    /** The finger has once been a whole row from where the lift began; see [slideReaches]. */
    private var rowsArmed = false

    /** The finger has gone sideways past the dead zone once; from then on the card follows it. */
    private var scrubbing = false

    /**
     * Where the dead zone is measured from until then: the lift's start, then wherever the finger
     * came onto the card or last went up or down on it. A poster low on the screen lifts with its
     * menu under the finger, which has to climb onto the card before it can look sideways.
     */
    private var scrubAnchor: Offset? = finger
    private var pending: (() -> Unit)? = null

    /** Replaced by another lift before it finished; its finger may still be down, but it runs nothing. */
    private var abandoned = false

    private val sectionSizes = menu.sections.map { it.size }

    /**
     * The finger moved. Nothing is hit until it has travelled [slop] from where the lift began —
     * the card lands under a still finger, and a tremor must not turn letting go into 打开 — and no
     * row until it has travelled a whole row (see [slideReaches]). Returns true when the finger
     * arrived on something new, or scrubbed the card on to another frame, which is what earns a tick.
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
        if (!rowsArmed) {
            val start = origin ?: finger
            rowsArmed = (finger - start).getDistance() >= maxOf(rowHeight, slop)
        }
        val found = liftHitAt(finger, laid, sectionSizes, rowHeight, separatorHeight, padding)
        val next = if (found is LiftHit.Row && !slideReaches(found.index)) LiftHit.None else found
        val scrubbed = scrubAlong(finger, next, laid.card, slop)
        if (next == hot) return scrubbed
        hot = next
        return next != LiftHit.None
    }

    /**
     * Whether the sliding finger may land on row [index] and run it by letting go.
     *
     * Not before it has once been a whole row away from where the lift began: a poster low on the
     * screen lifts with its menu right under the finger, and a drift past the touch slop — some 8 dp
     * — used to pick whatever row it rested on. Never a destructive row: letting go can happen by
     * itself, so those take a tap on the menu, which stays up.
     */
    private fun slideReaches(index: Int): Boolean = rowsArmed && menu.actions.getOrNull(index)?.destructive != true

    /**
     * 按住拖看: over the card, once the finger has gone sideways past [deadZone], the card shows the
     * frame for how far across it is; anywhere else it is its artwork again. True when the frame
     * changed. Nothing at all for a menu without [LiftMenu.scrub].
     */
    private fun scrubAlong(
        finger: Offset,
        hit: LiftHit,
        card: Rect,
        deadZone: Float,
    ): Boolean {
        val scrub = menu.scrub?.takeUnless { menu.anchored } ?: return false
        if (hit != LiftHit.Card) {
            scrubAnchor = null
            scrubFrame = -1
            return false
        }
        if (!scrubbing) {
            val anchor = scrubAnchor?.takeIf { card.contains(it) } ?: finger
            when (liftScrubAxis(anchor, finger, deadZone)) {
                DragAxis.Horizontal -> scrubbing = true
                DragAxis.Vertical -> scrubAnchor = finger
                DragAxis.Undecided -> scrubAnchor = anchor
            }
        }
        val frames = scrub.frameCount
        val stops = liftScrubStops(frames, card.width, scrubStep)
        if (!scrubbing || stops == 0) {
            scrubFrame = -1
            return false
        }
        scrubFraction = liftScrubFraction(finger.x, card)
        val frame = liftScrubFrame(liftScrubIndex(scrubFraction, stops), stops, frames)
        if (frame == scrubFrame) return false
        scrubFrame = frame
        return true
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
        scrubFrame = -1
    }

    fun select(action: ItemAction) {
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

    /** 一镜到底: the page this card is opening into, once [open] has named one. */
    var expansion by mutableStateOf<LiftExpansion?>(null)
        private set

    /**
     * The title just opened is [key]'s page, and the card can carry it there: it grows into the
     * page's hero instead of fading where it is. [velocity] is the finger's as it let go, in pixels
     * a second. Asked by the poster, from inside [open].
     */
    fun expandInto(
        key: MediaSharedElementKey,
        velocity: Offset,
    ) {
        if (abandoned || exit != LiftExit.FadeAway || menu.anchored) return
        // A scrubbed frame is not the page's picture; the card flies as the title's own art.
        scrubFrame = -1
        expansion = LiftExpansion(key, velocity)
        exit = LiftExit.Expand
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
