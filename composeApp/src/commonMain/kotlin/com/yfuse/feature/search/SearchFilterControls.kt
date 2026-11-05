@file:OptIn(ExperimentalLayoutApi::class)

package com.yfuse.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.controlBorder
import com.yfuse.core.designsystem.controlSurface
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.glassSelection
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberGlassSelectionStyle
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun SearchFilterLabel(
    text: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AppTypography.body.strong, color = LocalPalette.current.text)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = AppTypography.caption.regular,
            color = LocalPalette.current.sub2,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Long server/library labels retain their full Text semantics even when visually ellipsized. */
@Composable
internal fun SearchFilterGrid(
    options: List<SearchOption>,
    selectedId: String?,
    allLabel: String,
    onSelect: (String?) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 280.dp * fontScale) 2 else 1
        val choices = listOf<SearchOption?>(null) + options
        Column(
            Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.chunked(columns).forEach { choicesInRow ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choicesInRow.forEach { option ->
                        SearchFilterChoice(
                            label = option?.label ?: allLabel,
                            selected = selectedId == option?.id,
                            onClick = { onSelect(option?.id) },
                            modifier = Modifier.weight(1f),
                            weightedLabel = true,
                        )
                    }
                    repeat(columns - choicesInRow.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** All filter families share the same light glass, small check and 48 dp touch target. */
@Composable
internal fun SearchFilterChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weightedLabel: Boolean = false,
) {
    val palette = LocalPalette.current
    val selection = rememberGlassSelectionStyle()
    Row(
        modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 48.dp)
            .pressable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .then(
                if (selected) {
                    Modifier.glassSelection(AppShapes.chip, selection)
                } else {
                    Modifier.glass(AppShapes.chip, palette.controlSurface, palette.controlBorder)
                },
            ).padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = if (weightedLabel) Modifier.weight(1f, fill = false) else Modifier,
            style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
            color = if (selected) selection.contentColor else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(AppIcons.Check, null, tint = selection.contentColor, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun SearchFilterChips(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
