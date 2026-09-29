package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** How far the page dims under a lifted poster, on top of the blur. */
private const val LIFT_SCRIM_ALPHA = 0.56f

/**
 * With 降低透明度, or where nothing can be blurred: the dimming has to push the page back on its
 * own, and a plain dark plate reads better than a page half showing through.
 */
private const val LIFT_SOLID_SCRIM_ALPHA = 0.82f

/** Heavier than the dock's glass: the page behind a lifted poster is context, not content. */
private val LiftBlurRadius = 18.dp

/** A button's menu leaves the page readable around it: a light dim, no blur. */
private const val LIFT_ANCHORED_SCRIM_ALPHA = 0.28f

/** The menu panel leads its rows by a beat; the rows follow one after another. */
private const val LIFT_PANEL_DELAY_MS = 40
private const val LIFT_ROW_DELAY_MS = 70
private const val LIFT_ROW_STAGGER_MS = 18

/** How far a row drops into place as it appears. */
private val LiftRowTravel = 6.dp

/** The highlight under the row a finger is over. Deliberately not animated: it has to keep up. */
private const val LIFT_HOT_ALPHA = 0.08f

/** 按住拖看's position line, its dark edge, and the time's plate, inset from the card's edges. */
private val LiftScrubLineWidth = 2.dp
private val LiftScrubLineEdge = 1.dp
private val LiftScrubLineShade = Color.Black.copy(alpha = 0.32f)
private val LiftScrubLabelInset = 8.dp
private val LiftScrubLabelFill = Color(0xFF080C14).copy(alpha = 0.72f)

/**
 * Draws the lifted poster, when there is one: the blurred and dimmed page, the preview card,
 * and the menu. Placed once at the root of the app, above the tab bar and below the dialog
 * windows.
 *
 * @param backdrop the page's capture, the one the dock samples. Its source must be recording
 *   while [LiftMenuState.isOpen]; without it the page is only dimmed.
 */
@Composable
fun LiftMenuHost(
    state: LiftMenuState,
    backdrop: BackdropState? = null,
) {
    val session = state.session ?: return
    key(session) { LiftLayer(session, backdrop) }
}

@Composable
private fun LiftLayer(
    session: LiftSession,
    backdrop: BackdropState?,
) {
    val menu = session.menu
    val density = LocalDensity.current
    val palette = LocalPalette.current
    // 静息 and 减弱动态效果 get the card straight in its place and a fade: no sink, no morph.
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    // 0 is the poster in its grid, 1 the card in its place; the dimming follows it too.
    val lift = remember { Animatable(if (still) 1f else 0f) }
    // Everything at once: the reduced entrance, and an exit that leaves with the page.
    val presence = remember { Animatable(if (still) 0f else 1f) }
    val rowCount = menu.actions.size
    val revealMs = LIFT_ROW_DELAY_MS + LIFT_ROW_STAGGER_MS * (rowCount - 1).coerceAtLeast(0) + Motion.STANDARD
    // The clock the menu's panel and rows appear on, 0..1 across [revealMs].
    val reveal = remember { Animatable(if (still) 1f else 0f) }
    val firstRow = remember { FocusRequester() }
    // Opened from a keyboard or a screen reader rather than by a finger still on the glass.
    val openedWithoutFinger = remember { !session.holding }
    // 一镜到底: the card's way into the page it opened, once it opens one (LiftExpansion.kt).
    val flight = remember { LiftFlight() }
    val cardCornerPx = with(density) { AppShapes.card.radius.toPx() }

    LaunchedEffect(session) {
        if (openedWithoutFinger) runCatching { firstRow.requestFocus() }
        if (still) {
            presence.animateTo(1f, Motion.tween(Motion.REDUCED_FADE))
        } else {
            launch { reveal.animateTo(1f, Motion.tween(revealMs, easing = LinearEasing)) }
            lift.animateTo(1f, Motion.lift())
        }
    }
    val exit = session.exit
    LaunchedEffect(exit) {
        if (exit == LiftExit.None) return@LaunchedEffect
        val expansion = session.expansion
        val placed = session.placement
        when {
            exit == LiftExit.Expand && expansion != null && placed != null -> {
                // The rows go in the first 120 ms and the dimming on the card's own clock, while the
                // card itself becomes the page's hero. It sets off from wherever the lift has it.
                launch { reveal.animateTo(0f, Motion.tween(Motion.QUICK, easing = LinearEasing)) }
                launch { presence.animateTo(0f, Motion.oneTakeFade()) }
                lift.stop()
                val fraction = lift.value
                flight.carry(
                    expansion = expansion,
                    resting = lerp(session.source, placed.card, fraction),
                    cornerPx = cardCornerPx,
                    art = fraction.coerceIn(0f, 1f),
                    words = liftTextAlpha(fraction),
                    home = session.source,
                    homeCornerPx = cardCornerPx,
                )
            }
            exit == LiftExit.SettleBack && !still -> {
                launch { reveal.animateTo(0f, Motion.tween(Motion.QUICK, easing = LinearEasing)) }
                lift.animateTo(0f, Motion.lift())
            }
            else -> presence.animateTo(0f, Motion.tween(if (still) Motion.REDUCED_FADE else Motion.QUICK))
        }
        session.finish()
    }
    PlatformBackHandler(enabled = exit == LiftExit.None, onBack = session::dismiss)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics {
                paneTitle = menu.title
                dismiss(label = "关闭") {
                    session.dismiss()
                    true
                }
            },
    ) {
        // Root pixels, like the poster's bounds and the finger. The shell fills the window, so this
        // is zero in practice; it is measured rather than assumed.
        var origin by remember { mutableStateOf(Offset.Zero) }
        val layoutDirection = LocalLayoutDirection.current
        val statusBars = WindowInsets.statusBars
        val navigationBars = WindowInsets.navigationBars
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val insetLeft = navigationBars.getLeft(density, layoutDirection).toFloat()
        val insetRight = navigationBars.getRight(density, layoutDirection).toFloat()
        val insetTop = statusBars.getTop(density).toFloat()
        val insetBottom = navigationBars.getBottom(density).toFloat()
        val rowHeight = with(density) { LiftMenuRowHeight.toPx() }
        val separatorHeight = with(density) { LiftMenuSeparatorHeight.toPx() }
        val menuPadding = with(density) { LiftMenuPadding.toPx() }
        val scrubStep = with(density) { LiftScrubStep.toPx() }
        val placement =
            remember(session, width, height, origin, insetLeft, insetRight, insetTop, insetBottom, density) {
                val margin = with(density) { LiftMargin.toPx() }
                val bounds =
                    Rect(
                        left = origin.x + insetLeft + margin,
                        top = origin.y + insetTop + margin,
                        right = origin.x + width - insetRight - margin,
                        bottom = origin.y + height - insetBottom - margin,
                    )
                val cardWidth = minOf(with(density) { LiftCardMaxWidth.toPx() }, bounds.width).coerceAtLeast(0f)
                val menuHeight =
                    liftMenuContentHeight(menu.sections.map { it.size }, rowHeight, separatorHeight, menuPadding)
                if (menu.anchored) {
                    placeAnchoredMenu(
                        source = session.source,
                        bounds = bounds,
                        menuWidth = with(density) { LiftAnchoredMenuWidth.toPx() },
                        menuHeight = menuHeight,
                        gap = with(density) { LiftGap.toPx() },
                    )
                } else {
                    placeLift(
                        source = session.source,
                        bounds = bounds,
                        card = Size(cardWidth, cardWidth * LIFT_CARD_ASPECT),
                        menuWidth = cardWidth,
                        menuHeight = menuHeight,
                        gap = with(density) { LiftGap.toPx() },
                        rowHeight = rowHeight,
                    )
                }
            }
        SideEffect {
            session.placement = placement
            session.rowHeight = rowHeight
            session.separatorHeight = separatorHeight
            session.padding = menuPadding
            session.scrubStep = scrubStep
        }

        // The blur rides the same fade as the dimming, so a settling poster brings the page back
        // into focus as it lands rather than the whole blur switching off in one frame.
        val blurred = backdrop?.active == true && !menu.anchored
        val scrimAlpha =
            when {
                menu.anchored -> LIFT_ANCHORED_SCRIM_ALPHA
                blurred -> LIFT_SCRIM_ALPHA
                else -> LIFT_SOLID_SCRIM_ALPHA
            }
        Box(
            Modifier
                .fillMaxSize()
                .onPlaced { origin = it.positionInRoot() }
                .graphicsLayer { alpha = presence.value * lift.value.coerceIn(0f, 1f) }
                .then(
                    if (backdrop != null && blurred) {
                        Modifier.backdropBlur(backdrop, RectangleShape, radius = LiftBlurRadius, saturation = 1f)
                    } else {
                        Modifier
                    },
                ).background(palette.scrim.copy(alpha = scrimAlpha))
                .pointerInput(session) { detectTapGestures { session.dismiss() } },
        )

        // An anchored menu's card is the button itself, which never left the page.
        if (!menu.anchored) {
            LiftCard(
                session = session,
                fraction = { lift.value },
                presence = { presence.value },
                target = placement.card,
                origin = { origin },
                flight = flight,
            )
        }

        val menuRect = placement.menu
        Column(
            Modifier
                .offset {
                    IntOffset((menuRect.left - origin.x).roundToInt(), (menuRect.top - origin.y).roundToInt())
                }.size(with(density) { menuRect.width.toDp() }, with(density) { menuRect.height.toDp() })
                .graphicsLayer {
                    val elapsed = reveal.value * revealMs
                    alpha = presence.value * ((elapsed - LIFT_PANEL_DELAY_MS) / Motion.DISCLOSURE).coerceIn(0f, 1f)
                }.clip(AppShapes.card)
                .glass(shape = AppShapes.card, fill = palette.glassStrong, border = palette.border)
                .then(if (placement.menuScrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(vertical = LiftMenuPadding),
        ) {
            var index = 0
            menu.sections.forEachIndexed { section, actions ->
                if (section > 0) LiftSeparator()
                actions.forEach { action ->
                    val row = index++
                    LiftRow(
                        action = action,
                        highlighted = session.hot == LiftHit.Row(row),
                        appear = {
                            val start = LIFT_ROW_DELAY_MS + LIFT_ROW_STAGGER_MS * row
                            val local = ((reveal.value * revealMs - start) / Motion.STANDARD).coerceIn(0f, 1f)
                            Motion.Curve.transform(local)
                        },
                        onClick = { session.select(action) },
                        modifier = if (row == 0) Modifier.focusRequester(firstRow) else Modifier,
                    )
                }
            }
        }
    }
}

/**
 * The poster on its way to becoming the card, and the card. Its frame is interpolated every frame
 * from the poster's bounds to [target], in layout rather than as a scale, so the artwork is
 * cropped anew at each size instead of being stretched between a portrait tile and a landscape
 * card. The words are laid out once at the card's own width and only fade.
 *
 * Opening the title, the card goes on the same way into the page's hero along [flight]: frame,
 * corners, the landscape art and the words all follow that flight instead of the lift.
 */
@Composable
private fun LiftCard(
    session: LiftSession,
    fraction: () -> Float,
    presence: () -> Float,
    target: Rect,
    origin: () -> Offset,
    flight: LiftFlight,
) {
    val menu = session.menu
    val density = LocalDensity.current

    fun frame(): Rect = if (flight.flying) flight.frame() else lerp(session.source, target, fraction())

    val cardWidth = with(density) { target.width.toDp() }
    Box(
        Modifier
            .offset {
                val rect = frame()
                val at = origin()
                IntOffset((rect.left - at.x).roundToInt(), (rect.top - at.y).roundToInt())
            }.layout { measurable, _ ->
                val rect = frame()
                val width = rect.width.roundToInt().coerceAtLeast(1)
                val height = rect.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }.graphicsLayer {
                // Carrying the page open, the card keeps its own opacity while the dimming clears.
                alpha = if (flight.carrying) flight.alpha.value else presence()
                shape = if (flight.flying) ContinuousRoundedCornerShape(flight.cornerPx().toDp()) else AppShapes.card
                clip = true
            }.background(skeletonFill())
            .then(
                if (session.canOpen) {
                    Modifier.pressable(onClickLabel = "打开", onClick = session::open)
                } else {
                    Modifier
                },
            ),
    ) {
        // The poster's own art — already in memory, so the card leaves the grid looking exactly
        // like what was pressed.
        FallbackImage(
            urls = menu.artworkUrls,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        if (menu.backdropUrls.isNotEmpty()) {
            // Requested at the card's full size and revealed through the moving frame, so the
            // landscape art arrives sharp rather than scaled up from the first frame's poster size.
            // Growing past the card into the hero, it grows with the frame so it always covers it.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .wrapContentSize(unbounded = true)
                    .layout { measurable, _ ->
                        val grow = if (flight.flying) liftArtCover(frame(), target) else 1f
                        val width = (target.width * grow).roundToInt().coerceAtLeast(1)
                        val height = (target.height * grow).roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    }.graphicsLayer { alpha = if (flight.flying) flight.art() else fraction().coerceIn(0f, 1f) },
            ) {
                FallbackImage(
                    urls = menu.backdropUrls,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (flight.flying) flight.words() else liftTextAlpha(fraction()) }
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to Color(0xFF080C14).copy(alpha = 0.86f),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                // Unbounded both ways, so the words are measured once at the card's width while
                // the frame around them grows, instead of again on every frame of the lift.
                .wrapContentSize(align = Alignment.BottomStart, unbounded = true)
                .width(cardWidth)
                .graphicsLayer { alpha = if (flight.flying) flight.words() else liftTextAlpha(fraction()) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                menu.title,
                style = AppTypography.section.strong,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            menu.meta?.let { meta ->
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            menu.progress?.takeIf { it > 0f }?.let { watched ->
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(AppShapes.track)
                        .background(Color.White.copy(alpha = 0.24f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(watched.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(PrimaryGradient),
                    )
                }
            }
            menu.progressLabel?.let { label ->
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        menu.scrub?.let { scrub ->
            LiftScrubLayer(session = session, scrub = scrub, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * 按住拖看: while the lifting finger scrubs, the frame under it covers the whole card — artwork and
 * words — with a hairline where the finger is and the time above it. Only a change of
 * [LiftSession.scrubFrame] recomposes this; the line and the time follow the finger in drawing and
 * placement, so a move between two frames costs neither.
 */
@Composable
private fun LiftScrubLayer(
    session: LiftSession,
    scrub: LiftScrub,
    modifier: Modifier,
) {
    val frame = session.scrubFrame
    if (frame < 0) return
    val density = LocalDensity.current
    val line = with(density) { LiftScrubLineWidth.toPx() }
    val edge = with(density) { LiftScrubLineEdge.toPx() }
    val inset = with(density) { LiftScrubLabelInset.roundToPx() }
    Box(modifier) {
        scrub.Frame(index = frame, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val x = session.scrubFraction * size.width
                    // A dark edge keeps the white line visible over a bright frame.
                    drawRect(
                        color = LiftScrubLineShade,
                        topLeft = Offset(x - line / 2f - edge, 0f),
                        size = Size(line + edge * 2f, size.height),
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x - line / 2f, 0f),
                        size = Size(line, size.height),
                    )
                },
        )
        Text(
            scrub.label(frame),
            style = AppTypography.caption.strong,
            color = Color.White,
            maxLines = 1,
            modifier =
                Modifier
                    .layout { measurable, constraints ->
                        // Along the top, centred on the line and kept inside the card, clear of
                        // the finger, which is lower down where the poster was pressed.
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val width = constraints.maxWidth
                        layout(width, placeable.height + inset) {
                            val centred = (session.scrubFraction * width - placeable.width / 2f).roundToInt()
                            val last = (width - inset - placeable.width).coerceAtLeast(inset)
                            placeable.place(centred.coerceIn(inset, last), inset)
                        }
                    }.background(LiftScrubLabelFill, AppShapes.micro)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LiftRow(
    action: ItemAction,
    highlighted: Boolean,
    appear: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val travel = with(density) { LiftRowTravel.toPx() }
    val ink = if (action.destructive) palette.error else palette.text
    Row(
        modifier
            .fillMaxWidth()
            .height(LiftMenuRowHeight)
            .graphicsLayer {
                val shown = appear()
                alpha = shown
                translationY = (shown - 1f) * travel
            }.then(if (highlighted) Modifier.background(palette.text.copy(alpha = LIFT_HOT_ALPHA)) else Modifier)
            .pressable(pressedScale = 1f, tintOnPress = true, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        action.icon?.let { icon ->
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Text(
            action.label,
            style = AppTypography.body.medium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action.detail?.let { detail ->
            Text(
                detail,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LiftSeparator() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(LiftMenuSeparatorHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.border),
        )
    }
}
