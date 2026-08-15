package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.touchTarget

/** Shared building blocks used by player settings, danmaku, watch-together, and diagnostics. */
@Composable
internal fun DiagnosticRow(
    label: String,
    value: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = GlassShapes.thumb,
                fill = Color.White.copy(alpha = 0.06f),
                border = Color.White.copy(alpha = 0.10f),
            ).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AppTypography.caption.medium, color = Color.White.copy(alpha = 0.54f))
        Text(
            value,
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.90f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Text(
                label,
                style = if (active) AppTypography.caption.strong else AppTypography.caption.medium,
                color = if (active) accent.accent else Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .glass(
                            shape = GlassShapes.thumb,
                            fill = if (active) accent.container else Color.White.copy(alpha = 0.06f),
                            border = if (active) accent.border else Color.White.copy(alpha = 0.10f),
                        ).noRippleClickable { onSelect(index) }
                        .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun GroupLabel(text: String) {
    Text(
        text,
        style = AppTypography.caption.medium,
        color = Color.White.copy(alpha = 0.48f),
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
}

@Composable
internal fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = GlassShapes.thumb,
                fill = if (selected) accent.container else Color.White.copy(alpha = 0.06f),
                border = if (selected) accent.border else Color.White.copy(alpha = 0.10f),
            ).noRippleClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
            color = if (selected) accent.accent else Color.White.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        when {
            actionLabel != null ->
                Text(
                    actionLabel,
                    style = AppTypography.caption.medium,
                    color = DarkPalette.error,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .noRippleClickable(onAction)
                            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                )

            selected -> Icon(AppIcons.Check, null, tint = accent.accent, modifier = Modifier.size(12.dp))
        }
    }
}

/** Player overlays use a press response without drawing a ripple over the video. */
@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = pressable(onClick = onClick).touchTarget()
