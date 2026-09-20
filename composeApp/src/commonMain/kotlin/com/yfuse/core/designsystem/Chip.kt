package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * A selectable pill — a filter, a category, a preset. One click toggles it on or off.
 *
 * Consolidates five near-identical private implementations that had drifted apart (搜索的
 * TypeChip、媒体库的 GenreChip、下载页的 DownloadChip、剧集批处理的 PresetChip——此前只有
 * `heightIn(44.dp)`，未达到 48dp 的最小触控目标——以及日历的筛选 chip)。[Role.Tab] is fixed
 * rather than exposed: [selected] is mandatory, and a chip that can be selected is exactly
 * what Material's own `FilterChip` announces as a tab.
 *
 * @param leadingIcon optional glyph before the label, tinted to match the label colour.
 */
@Composable
fun YfChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val ink = selectionColor(if (selected) accent.accent else palette.body)
    Row(
        modifier =
            modifier
                .pressable(
                    enabled = enabled,
                    haptic = HapticSignal.Select,
                    role = Role.Tab,
                    focusShape = AppShapes.chip,
                    onClickLabel = onClickLabel,
                    onClick = onClick,
                ).semantics { this.selected = selected }
                .touchTarget()
                .glass(
                    shape = AppShapes.chip,
                    fill = selectionColor(if (selected) accent.container else palette.card2),
                    border = selectionColor(if (selected) accent.border else palette.border),
                ).alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = Dimens.space.md, vertical = Dimens.space.sm),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        }
        Text(
            label,
            style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
            color = ink,
            maxLines = 1,
        )
    }
}
