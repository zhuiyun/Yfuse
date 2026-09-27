package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.Haptics
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.sync.WatchSticker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** 按住拖送 opens after this long rather than the platform's long press: the doc's 350 ms. */
private const val CAST_HOLD_MS = 350L

/** A held key keeps the chrome up by poking its hide timer this often, finger moving or not. */
private const val HOLD_KEEP_ALIVE_MS = 1_000L

private val FanRadius = 92.dp
private val StickerDisc = 44.dp
private val StickerReach = 30.dp
private val PickMargin = 8.dp
private val DeviceRowWidth = 224.dp
private val DeviceRowHeight = 48.dp
private val DeviceRowGap = 6.dp
private val DeviceSideSlop = 24.dp

/** How far behind the row above each device row starts to appear, as a share of the whole. */
private const val ROW_STAGGER = 0.14f

/**
 * What the player root adds to the chrome without the chrome having to know how: 点弹幕's claim on
 * the picture's taps, the top bar's 旋转锁, and the press-and-slide pickers behind 聊天 and 投屏.
 */
@Immutable
internal class PlayerChromeExtras(
    /** 点弹幕: offered each single tap on the picture that nothing else wanted; true claims it. */
    val onPictureTap: (Offset) -> Boolean = { false },
    val rotationLock: PlayerRotationLock? = null,
    /** Where the pickers below are drawn; null leaves both keys tap-only. */
    val quickPick: PlayerQuickPickHost? = null,
    val stickers: StickerQuickPick? = null,
    val cast: CastQuickPick? = null,
)

/** 一起看贴纸轮盘: what the chat key's fan offers and how a pick goes out. */
@Immutable
internal class StickerQuickPick(
    val stickers: List<WatchSticker>,
    /** False while the room reconnects: holding the key then does what a tap does. */
    val canSend: Boolean,
    /** The room's ordinary sticker path — a chat message carrying the token; false if refused. */
    val onSend: (WatchSticker) -> Boolean,
)

/** 按住拖送: the cast key's list and what releasing on a row does. */
@Immutable
internal class CastQuickPick(
    val targets: List<QuickCastTarget>,
    /** The list has opened: the moment to start looking for the devices on it. */
    val onOpen: () -> Unit,
    /** Casts, or hands off, to [QuickCastTarget]; the outcome answers for itself when it is known. */
    val onPick: (QuickCastTarget) -> Unit,
)

internal enum class QuickPickKind { Stickers, Devices }

/** One pick in progress: what is open, and the key it hangs from, in root pixels. */
internal class QuickPickSession(
    val kind: QuickPickKind,
    val anchor: Rect,
    val stickers: List<WatchSticker> = emptyList(),
)

/**
 * The layer the top bar's pickers are drawn in, over the whole player. The key keeps the finger
 * — a pointer stays with the node it went down on wherever it moves — and tells this what is open
 * and what the finger is over; the layer only draws. Geometry comes from the same functions for
 * both, so what is drawn is what is hit.
 */
@Stable
internal class PlayerQuickPickHost {
    var session: QuickPickSession? by mutableStateOf(null)
        private set

    var hovered: Int? by mutableStateOf(null)
        private set

    /** The layer, in root pixels, and the density it was laid out at. */
    var bounds: Rect = Rect.Zero
    var density: Float = 1f

    fun open(next: QuickPickSession) {
        session = next
        hovered = null
    }

    fun close() {
        session = null
        hovered = null
    }

    /** Moves the highlight; true when it moved onto another item, which is worth a tick. */
    fun hover(index: Int?): Boolean {
        val moved = index != hovered && index != null
        hovered = index
        return moved
    }

    fun stickerCenters(session: QuickPickSession): List<Offset> =
        keepInside(
            centers = fanCenters(session.anchor.center, session.stickers.size, FanRadius.value * density),
            itemRadius = StickerDisc.value * density / 2f,
            bounds = bounds,
            margin = PickMargin.value * density,
        )

    fun deviceRows(
        session: QuickPickSession,
        count: Int,
    ): List<Rect> =
        columnRects(
            anchor = session.anchor,
            count = count,
            rowWidth = DeviceRowWidth.value * density,
            rowHeight = DeviceRowHeight.value * density,
            gap = DeviceRowGap.value * density,
            bounds = bounds,
            margin = PickMargin.value * density,
        )

    fun indexAt(
        finger: Offset,
        count: Int,
    ): Int? {
        val open = session ?: return null
        return when (open.kind) {
            QuickPickKind.Stickers -> discAt(finger, stickerCenters(open), StickerReach.value * density)
            QuickPickKind.Devices ->
                rowAt(
                    finger = finger,
                    rows = deviceRows(open, count),
                    gap = DeviceRowGap.value * density,
                    sideSlop = DeviceSideSlop.value * density,
                )
        }
    }
}

/** The chat key's hold: after the platform's long press its fan opens; a tap still opens 聊天. */
@Composable
internal fun Modifier.stickerFanKey(
    extras: PlayerChromeExtras,
    onActivity: () -> Unit,
): Modifier {
    val pick = extras.stickers
    val haptics = LocalHaptics.current
    return pressAndSlide(
        host = extras.quickPick?.takeIf { pick != null },
        holdMillis = null,
        onActivity = onActivity,
        open = { anchor ->
            pick
                ?.takeIf { it.canSend && it.stickers.isNotEmpty() }
                ?.let { QuickPickSession(QuickPickKind.Stickers, anchor, it.stickers) }
        },
        count = { session -> session.stickers.size },
        onPick = { session, index ->
            val sticker = session.stickers.getOrNull(index)
            if (pick != null && sticker != null) sendSticker(pick, sticker, haptics)
        },
    )
}

/** The cast key's hold: after [CAST_HOLD_MS] the device list opens; a tap still opens 投屏. */
@Composable
internal fun Modifier.castListKey(
    extras: PlayerChromeExtras,
    onActivity: () -> Unit,
): Modifier {
    val cast = extras.cast
    return pressAndSlide(
        host = extras.quickPick?.takeIf { cast != null },
        holdMillis = CAST_HOLD_MS,
        onActivity = onActivity,
        open = { anchor ->
            cast?.let {
                it.onOpen()
                QuickPickSession(QuickPickKind.Devices, anchor)
            }
        },
        count = { cast?.targets?.size ?: 0 },
        onPick = { _, index ->
            val target = cast?.targets?.getOrNull(index)
            if (cast != null && target != null) cast.onPick(target)
        },
    )
}

/** The fan's stickers as a screen reader's actions on the chat key, so no hold is needed. */
internal fun stickerKeyActions(
    extras: PlayerChromeExtras,
    haptics: Haptics,
): List<CustomAccessibilityAction> {
    val pick = extras.stickers?.takeIf { it.canSend } ?: return emptyList()
    return pick.stickers.map { sticker ->
        CustomAccessibilityAction("发送贴纸：${sticker.label}") {
            sendSticker(pick, sticker, haptics)
            true
        }
    }
}

/** The list's devices as a screen reader's actions on the cast key. */
internal fun castKeyActions(extras: PlayerChromeExtras): List<CustomAccessibilityAction> {
    val cast = extras.cast ?: return emptyList()
    return cast.targets.map { target ->
        CustomAccessibilityAction(target.actionLabel) {
            cast.onPick(target)
            true
        }
    }
}

private fun sendSticker(
    pick: StickerQuickPick,
    sticker: WatchSticker,
    haptics: Haptics,
) {
    haptics.play(if (pick.onSend(sticker)) HapticSignal.Confirm else HapticSignal.Reject)
}

/**
 * Hold, slide, let go. Chained **before** the key's own click, like `liftable`: until the hold
 * fires it only listens, so a tap is still the key's; once it fires it takes every later event
 * on the way down, before the click underneath, which then never happens.
 *
 * @param holdMillis how long the press must stay still; null for the platform's long press.
 * @param open builds what the hold opens from the key's bounds; null lets the press go on as a
 *   press, so a key that has nothing to offer right now still opens its panel on release.
 */
@Composable
private fun Modifier.pressAndSlide(
    host: PlayerQuickPickHost?,
    holdMillis: Long?,
    onActivity: () -> Unit,
    open: (Rect) -> QuickPickSession?,
    count: (QuickPickSession) -> Int,
    onPick: (QuickPickSession, Int) -> Unit,
): Modifier {
    if (host == null) return this
    val latestOpen by rememberUpdatedState(open)
    val latestCount by rememberUpdatedState(count)
    val latestPick by rememberUpdatedState(onPick)
    val latestActivity by rememberUpdatedState(onActivity)
    val latestHaptics by rememberUpdatedState(LocalHaptics.current)
    val scope = rememberCoroutineScope()
    // A plain holder: nothing is drawn from it.
    val own = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onPlaced { own[0] = it }
        .pointerInput(host) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val held = awaitStillHold(down, holdMillis ?: viewConfiguration.longPressTimeoutMillis)
                val self = own[0]?.takeIf { it.isAttached }
                val session = if (held != null && self != null) latestOpen(self.boundsInRoot()) else null
                if (held == null || self == null || session == null) return@awaitEachGesture
                host.open(session)
                latestHaptics.play(HapticSignal.LongPress)
                latestActivity()
                val keepAlive =
                    scope.launch {
                        while (true) {
                            delay(HOLD_KEEP_ALIVE_MS)
                            latestActivity()
                        }
                    }
                var pointer = held.id
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
                        val index = host.indexAt(at, latestCount(session))
                        if (!change.pressed) {
                            host.close()
                            if (index != null) latestPick(session, index)
                            break
                        }
                        if (host.hover(index)) latestHaptics.play(HapticSignal.Tick)
                    }
                } finally {
                    // Taken away mid-hold — the key left, the window lost focus: nothing is picked.
                    keepAlive.cancel()
                    if (host.session === session) host.close()
                }
            }
        }
}

/** The press still held, and still where it went down, after [holdMillis]; null otherwise. */
private suspend fun AwaitPointerEventScope.awaitStillHold(
    down: PointerInputChange,
    holdMillis: Long,
): PointerInputChange? {
    val slop = viewConfiguration.touchSlop
    var latest = down
    val ended =
        withTimeoutOrNull(holdMillis) {
            var over = false
            while (!over) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                over = change == null || !change.pressed || (change.position - down.position).getDistance() > slop
                if (change != null) latest = change
            }
            true
        }
    return if (ended == null) latest else null
}

/**
 * Draws whatever a top-bar key has open — the sticker fan, the device list — above the whole
 * player. It takes no touches: the finger that opened it still belongs to the key.
 */
@Composable
internal fun PlayerQuickPickLayer(extras: PlayerChromeExtras) {
    val host = extras.quickPick ?: return
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                host.bounds = it.boundsInRoot()
                host.density = density.density
            },
    ) {
        val session = host.session
        if (session != null) {
            when (session.kind) {
                QuickPickKind.Stickers -> StickerFan(host, session)
                QuickPickKind.Devices -> DeviceList(host, session, extras.cast?.targets.orEmpty())
            }
        }
    }
}

@Composable
private fun rememberAppearance(
    session: QuickPickSession,
    still: Boolean,
): Animatable<Float, AnimationVector1D> {
    val appear = remember(session) { Animatable(0f) }
    LaunchedEffect(appear, still) {
        appear.animateTo(1f, if (still) Motion.tween(Motion.REDUCED_FADE) else Motion.lift())
    }
    return appear
}

@Composable
private fun StickerFan(
    host: PlayerQuickPickHost,
    session: QuickPickSession,
) {
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val appear = rememberAppearance(session, still)
    val accent = LocalAccentColors.current.accent
    val origin = host.bounds.topLeft
    val centers = host.stickerCenters(session).map { it - origin }
    val pivot = session.anchor.center - origin
    val half = with(LocalDensity.current) { StickerDisc.toPx() / 2f }
    session.stickers.forEachIndexed { index, sticker ->
        val hovered = host.hovered == index
        val grow by animateFloatAsState(
            targetValue = if (hovered) 1.28f else 1f,
            animationSpec = if (still) Motion.tween(Motion.REDUCED_FADE) else Motion.lift(),
            label = "sticker-fan-hover",
        )
        Box(
            Modifier
                .offset {
                    // Out of the key along its spoke; under 减弱动态效果 it is simply there.
                    val at = if (still) centers[index] else lerp(pivot, centers[index], appear.value)
                    IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt())
                }.size(StickerDisc)
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    val scale = grow * if (still) 1f else appear.value
                    scaleX = scale
                    scaleY = scale
                }.glass(
                    shape = CircleShape,
                    fill = if (hovered) accent.copy(alpha = 0.58f) else PlayerTokens.panelFill,
                    border = if (hovered) Color.White.copy(alpha = 0.4f) else PlayerTokens.chipBorder,
                ),
            contentAlignment = Alignment.Center,
        ) {
            WatchStickerGlyph(sticker, sizeSp = 20f, animated = hovered)
        }
    }
    // The name of the one under the finger, just below it: the glyph alone can be ambiguous.
    val named = host.hovered?.let { index -> session.stickers.getOrNull(index)?.let { it to centers[index] } }
    if (named != null) {
        val (sticker, at) = named
        Text(
            sticker.label,
            style = AppTypography.caption.medium,
            color = Color.White,
            maxLines = 1,
            modifier =
                Modifier
                    .centredAt(Offset(at.x, at.y + half * 1.4f))
                    .glass(shape = GlassShapes.chip, fill = PlayerTokens.panelFill, border = PlayerTokens.chipBorder)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DeviceList(
    host: PlayerQuickPickHost,
    session: QuickPickSession,
    targets: List<QuickCastTarget>,
) {
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val appear = rememberAppearance(session, still)
    val accent = LocalAccentColors.current.accent
    val origin = host.bounds.topLeft
    val rows = host.deviceRows(session, maxOf(targets.size, 1)).map { it.translate(-origin.x, -origin.y) }
    val drop = with(LocalDensity.current) { 12.dp.toPx() }
    val shown = targets.ifEmpty { listOf(null) }
    shown.forEachIndexed { index, target ->
        val row = rows[index]
        val hovered = target != null && host.hovered == index
        Row(
            Modifier
                .offset { IntOffset(row.left.roundToInt(), row.top.roundToInt()) }
                .size(DeviceRowWidth, DeviceRowHeight)
                .graphicsLayer {
                    // Each row a beat after the one above, dropping out of the key.
                    val progress =
                        ((appear.value * (1f + ROW_STAGGER * (shown.size - 1))) - ROW_STAGGER * index).coerceIn(0f, 1f)
                    alpha = progress
                    if (!still) translationY = -drop * (1f - progress)
                }.glass(
                    shape = GlassShapes.chip,
                    fill = if (hovered) accent.copy(alpha = 0.58f) else PlayerTokens.drawerFill,
                    border = if (hovered) Color.White.copy(alpha = 0.4f) else PlayerTokens.chipBorder,
                ).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                AppIcons.Cast,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (target?.found == false) 0.55f else 1f),
                modifier = Modifier.size(16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    target?.name ?: "正在查找附近的设备…",
                    style = AppTypography.caption.strong,
                    color = Color.White.copy(alpha = if (target == null) 0.6f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (target != null) {
                    Text(
                        when {
                            target.active -> "正在投屏"
                            !target.found -> "${target.kind.caption} · 查找中"
                            else -> target.kind.caption
                        },
                        style = AppTypography.caption.regular,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
            if (target?.active == true) {
                Icon(AppIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Places this node's content with its top-centre at [point] in the parent's frame. */
private fun Modifier.centredAt(point: Offset): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(placeable.width, placeable.height) {
            placeable.place((point.x - placeable.width / 2f).roundToInt(), point.y.roundToInt())
        }
    }
