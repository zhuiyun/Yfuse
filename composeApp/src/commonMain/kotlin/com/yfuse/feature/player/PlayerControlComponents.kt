package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
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
            .padding(horizontal = 10.dp, vertical = 7.dp),
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
        Modifier
            .fillMaxWidth()
            .glass(
                shape = AppShapes.pill,
                fill = Color.White.copy(alpha = 0.07f),
                border = Color.White.copy(alpha = 0.12f),
            ).padding(3.dp),
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
                            shape = AppShapes.pill,
                            fill = if (active) accent.container else Color.Transparent,
                            border = if (active) accent.border else null,
                        ).noRippleClickable { onSelect(index) }
                        .padding(vertical = 9.dp),
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
    detailLabel: String? = null,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = GlassShapes.thumb,
                fill = if (selected) accent.container else Color.White.copy(alpha = 0.045f),
                border = if (selected) accent.border else Color.White.copy(alpha = 0.07f),
            ).noRippleClickable(onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
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

            detailLabel != null ->
                Text(
                    detailLabel,
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.48f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )

            selected ->
                Box(
                    Modifier
                        .padding(start = 10.dp)
                        .size(6.dp)
                        .background(accent.accent, CircleShape),
                )
        }
    }
}

@Composable
internal fun PopupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.10f)),
    )
}

@Composable
internal fun PopupToggleHeader(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .noRippleClickable(onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = AppTypography.section.medium,
            color = Color.White.copy(alpha = 0.94f),
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF55C766),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.82f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.12f),
                ),
        )
    }
}

@Composable
internal fun PopupMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    detail: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Row(
        Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick)
            .padding(horizontal = 5.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accent.accent else Color.White.copy(alpha = 0.86f),
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTypography.body.strong,
                color = if (selected) accent.accent else Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        when {
            detail != null ->
                Text(
                    detail,
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.50f),
                    maxLines = 1,
                )

            selected ->
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = accent.accent,
                    modifier = Modifier.size(19.dp),
                )

            else ->
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.size(17.dp),
                )
        }
    }
}

/** Player overlays use a press response without drawing a ripple over the video. */
@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = pressable(onClick = onClick).touchTarget()
