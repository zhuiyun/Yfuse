package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

// ------------------------------------------------------------------ the selection (5.4)
//
// What every 多选 does to its set of keys, in plain Kotlin so it can be tested without a screen.

/** This selection with [key] flipped in or out: a tap on a row while 多选 is on. */
fun <K> Set<K>.toggling(key: K): Set<K> = if (key in this) this - key else this + key

/** Whether every one of [keys] is selected, which turns 全选 into 取消全选. Never, for no keys. */
fun <K> Set<K>.coversAll(keys: Collection<K>): Boolean = keys.isNotEmpty() && keys.all { it in this }

/**
 * 全选 over [keys], or 取消全选 when they are all selected already. Only [keys] change: a row a
 * filter hides keeps whatever it had.
 */
fun <K> Set<K>.selectingAll(keys: Collection<K>): Set<K> = if (coversAll(keys)) this - keys.toSet() else this + keys

// ------------------------------------------------------------------ the mark

/**
 * Whether one item is in 多选's selection: a filled accent disc with a check when it is, an empty
 * ring when not. [onArtwork] for a mark over a poster, whose ring is white over a dark wash so it
 * holds on any picture; otherwise the ring takes the page's own ink.
 *
 * For the eye only. The item it sits on carries the selection for a screen reader, as a checkbox's
 * state; a mark read out as well would say it twice.
 */
@Composable
fun SelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
) {
    val accent = LocalAccentColors.current
    val ring =
        when {
            selected -> accent.accent
            onArtwork -> Color.White.copy(alpha = 0.92f)
            else -> LocalPalette.current.sub2
        }
    val fill =
        when {
            selected -> accent.accent
            onArtwork -> Color.Black.copy(alpha = 0.28f)
            else -> Color.Transparent
        }
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(AppIcons.Check, contentDescription = null, tint = accent.onAccent, modifier = Modifier.size(12.dp))
        }
    }
}

// ------------------------------------------------------------------ the bar

/**
 * One thing 多选's bar does to what is selected: 暂停, 删除, 标记已看. With nothing selected that it
 * can act on it is not [enabled], and stays in its place, dimmed, rather than the bar reflowing each
 * time the selection changes.
 */
@Immutable
class SelectionAction(
    val label: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    /** What a screen reader says the button does, where [label] alone is short: 删除所选下载. */
    val onClickLabel: String = label,
    val onClick: () -> Unit,
)

/**
 * 多选's actions (5.4), docked at the foot of a page for as long as it is selecting: 下载, and
 * 继续观看's 全部. Put it after the list in a column, below the list rather than in it or over it.
 *
 * In the list, it pushed every row under it down as it opened, and a 长按拖选 opens it under a held
 * finger: the row pressed slid away, and the next small move swept the row that arrived instead.
 * Below the list, the list only gives up its foot, which keeps each row where it is and the sweep's
 * autoscroll band above the bar rather than under it. It stays for the whole mode, its buttons
 * dimmed while there is nothing for them, so a sweep that empties the selection does not take the
 * bar away and move the rows the other way.
 *
 * It grows in over [Motion.DISCLOSURE], as the pages' other reveals do; 减少动画 and 静息 fade it in
 * place instead.
 */
@Composable
fun SelectionActionBar(
    visible: Boolean,
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
) {
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter =
            if (still) {
                fadeIn(Motion.tween(Motion.REDUCED_FADE))
            } else {
                fadeIn(Motion.tween(Motion.DISCLOSURE)) + expandVertically(Motion.tween(Motion.DISCLOSURE))
            },
        exit =
            if (still) {
                fadeOut(Motion.tween(Motion.REDUCED_FADE))
            } else {
                fadeOut(Motion.tween(Motion.DISCLOSURE)) + shrinkVertically(Motion.tween(Motion.DISCLOSURE))
            },
    ) {
        val palette = LocalPalette.current
        // The page runs under the navigation bar; the bar is the page's last thing and clears it.
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val surface =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = Dimens.pageHorizontal,
                    end = Dimens.pageHorizontal,
                    top = Dimens.space.sm,
                    bottom = navigationBar + Dimens.space.md,
                ).glass(AppShapes.card, palette.card2, palette.border)
                .padding(10.dp)
        // Under 大号文字 three labels no longer fit side by side, so each takes the full width.
        if (LocalDensity.current.fontScale >= 1.3f) {
            Column(surface, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { SelectionActionButton(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(surface, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { SelectionActionButton(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SelectionActionButton(
    action: SelectionAction,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    Text(
        action.label,
        style = AppTypography.body.strong,
        color =
            when {
                !action.enabled -> palette.hint
                action.destructive -> palette.error
                else -> LocalAccentColors.current.accent
            },
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .pressable(enabled = action.enabled, onClickLabel = action.onClickLabel, onClick = action.onClick)
                .touchTarget()
                .glass(AppShapes.chip, palette.card3, palette.border)
                .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}
