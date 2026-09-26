package com.yfuse.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PillSwitch
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.pressable
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Opens 编辑首页, where the page's shelves can be put in order and hidden. Null where the page
 * cannot be edited (a benchmark fixture); a shelf title then keeps no long press.
 */
internal val LocalHomeShelfEdit = staticCompositionLocalOf<(() -> Unit)?> { null }

/** One shelf as 编辑首页 lists it. */
@Immutable
internal class HomeShelfOption(
    val id: String,
    val title: String,
)

/** Where a row dragged [offset] pixels from position [from] would land among [count] rows. */
internal fun shelfDropIndex(
    from: Int,
    offset: Float,
    rowHeight: Float,
    count: Int,
): Int = (from + (offset / rowHeight).roundToInt()).coerceIn(0, (count - 1).coerceAtLeast(0))

/**
 * How many rows the row at [index] steps aside while the row from [from] is held over [target]:
 * up one for those it passes going down, down one for those it passes going up.
 */
internal fun shelfRowShift(
    index: Int,
    from: Int,
    target: Int,
): Int =
    when {
        from < 0 || index == from -> 0
        target > from && index in (from + 1)..target -> -1
        target < from && index in target until from -> 1
        else -> 0
    }

/** One row of 编辑首页; the drag moves in whole rows of it. */
private val ShelfRowHeight = 56.dp

/**
 * 编辑首页: the shelves in their order, each with its switch. Drag a row by its handle to move
 * it, tap a row to show or hide its shelf. Every change applies as it is made, so the page behind
 * is already in its new order when the sheet closes. A screen reader gets 上移 and 下移 on every
 * row instead of the drag.
 */
@Composable
internal fun HomeShelfEditorSheet(
    options: List<HomeShelfOption>,
    hidden: Set<String>,
    onMove: (id: String, to: Int) -> Unit,
    onToggle: (id: String, visible: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val rowPx = with(LocalDensity.current) { ShelfRowHeight.toPx() }
    val latestOptions by rememberUpdatedState(options)
    // The row in the finger, how far it has come, and the slot it would drop into now.
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(-1) }

    fun finish(commit: Boolean) {
        val id = dragging ?: return
        val from = latestOptions.indexOfFirst { it.id == id }
        if (commit && from >= 0 && target >= 0 && target != from) {
            onMove(id, target)
            haptics.play(HapticSignal.Confirm)
        }
        dragging = null
        offset = 0f
        target = -1
    }

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = "编辑首页", subtitle = "拖动右侧把手排序，轻点显示或隐藏", onClose = onDismiss)
        Column {
            val from = dragging?.let { id -> options.indexOfFirst { it.id == id } } ?: -1
            options.forEachIndexed { index, option ->
                key(option.id) {
                    val held = option.id == dragging
                    val shift by animateFloatAsState(
                        targetValue = shelfRowShift(index, from, target) * rowPx,
                        // Rows step aside while a drag is live; when it ends they are already where
                        // the drop puts them, so they arrive without moving.
                        animationSpec =
                            if (dragging != null && !still) {
                                Motion.tween(Motion.QUICK, easing = Motion.Curve)
                            } else {
                                snap()
                            },
                        label = "shelfRowShift",
                    )
                    ShelfEditorRow(
                        option = option,
                        visible = option.id !in hidden,
                        held = held,
                        onToggle = { onToggle(option.id, it) },
                        onMoveBy = { step ->
                            val at = latestOptions.indexOfFirst { it.id == option.id }
                            val to = (at + step).coerceIn(0, latestOptions.size - 1)
                            if (at >= 0 && to != at) onMove(option.id, to)
                        },
                        handle =
                            Modifier.pointerInput(option.id) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        dragging = option.id
                                        offset = 0f
                                        target = latestOptions.indexOfFirst { it.id == option.id }
                                        haptics.play(HapticSignal.DragStart)
                                    },
                                    onDragEnd = { finish(commit = true) },
                                    onDragCancel = { finish(commit = false) },
                                    onVerticalDrag = { change, amount ->
                                        change.consume()
                                        offset += amount
                                        val start = latestOptions.indexOfFirst { it.id == option.id }
                                        val next = shelfDropIndex(start, offset, rowPx, latestOptions.size)
                                        if (next != target) {
                                            target = next
                                            haptics.play(HapticSignal.Tick)
                                        }
                                    },
                                )
                            },
                        modifier =
                            Modifier
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (held) offset else shift
                                    val lift = if (held && !still) 1.02f else 1f
                                    scaleX = lift
                                    scaleY = lift
                                },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OverlayActionRow(label = "恢复默认", description = "默认顺序，全部显示", onClick = onReset)
    }
}

@Composable
private fun ShelfEditorRow(
    option: HomeShelfOption,
    visible: Boolean,
    held: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveBy: (Int) -> Unit,
    handle: Modifier,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .height(ShelfRowHeight)
            .clip(AppShapes.chip)
            .background(if (held) palette.card2 else palette.card2.copy(alpha = 0f))
            .pressable(
                role = Role.Switch,
                haptic = if (visible) HapticSignal.ToggleOff else HapticSignal.ToggleOn,
                onClick = { onToggle(!visible) },
            ).semantics {
                toggleableState = ToggleableState(visible)
                customActions =
                    listOf(
                        CustomAccessibilityAction("上移") {
                            onMoveBy(-1)
                            true
                        },
                        CustomAccessibilityAction("下移") {
                            onMoveBy(1)
                            true
                        },
                    )
            }.padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            option.title,
            style = AppTypography.body.medium,
            color = if (visible) palette.text else palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PillSwitch(checked = visible)
        Box(handle.size(ShelfRowHeight), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Menu, contentDescription = null, tint = palette.hint, modifier = Modifier.size(18.dp))
        }
    }
}
