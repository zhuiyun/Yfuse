package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.ThemeText as Text

/** Stable choices with one live preview and a glimpse of the next card to invite a swipe. */
@Composable
internal fun <T> AnimationCardList(
    options: List<T>,
    selected: T,
    key: (T) -> String,
    label: (T) -> String,
    description: (T) -> String,
    onSelect: (T) -> Unit,
    category: (T) -> String = { "动画预览" },
    preview: @Composable (T, Boolean) -> Unit,
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    LaunchedEffect(selectedIndex, reduceMotion) {
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (item == null ||
            item.offset < layout.viewportStartOffset ||
            item.offset + item.size > layout.viewportEndOffset
        ) {
            if (reduceMotion) listState.scrollToItem(selectedIndex) else listState.animateScrollToItem(selectedIndex)
        }
    }
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = minOf(184.dp, maxWidth * 0.76f)
        LazyRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            state = listState,
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(options, key = key) { animation ->
                val chosen = animation == selected
                Column(
                    Modifier
                        .width(cardWidth)
                        .testTag(key(animation))
                        .pressable(
                            role = Role.RadioButton,
                            haptic = HapticSignal.Select,
                            focusShape = AppShapes.card,
                            onClick = { onSelect(animation) },
                        ).semantics { this.selected = chosen }
                        .clip(AppShapes.card)
                        .background(if (chosen) accent.container else palette.card2)
                        .border(
                            if (chosen) 2.dp else 1.dp,
                            if (chosen) accent.border else palette.border,
                            AppShapes.card,
                        ).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            category(animation),
                            modifier = Modifier.weight(1f),
                            color = palette.sub,
                            style = AppTypography.caption.regular,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chosen) {
                            Text(
                                "已选",
                                color = accent.accent,
                                style = AppTypography.caption.strong,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(108.dp)
                            .clip(AppShapes.thumb)
                            .background(palette.background)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) {
                        preview(animation, chosen && !scrolling)
                    }
                    Text(
                        label(animation),
                        color = if (chosen) accent.accent else palette.text,
                        style = AppTypography.body.strong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        description(animation),
                        color = palette.sub,
                        style = AppTypography.caption.regular,
                        minLines = 3,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
