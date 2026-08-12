package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable

@Composable
internal fun AppIconSheet(
    current: AppIconVariant,
    onSelect: (AppIconVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "APP 图标",
            subtitle = "直接预览当前 Logo 与旧版 Logo；启动器可能需要几秒刷新",
            onClose = onDismiss,
        )
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            AppIconVariant.entries.forEach { option ->
                val isSelected = option == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(
                            haptic = HapticSignal.Select,
                            role = Role.RadioButton,
                            focusShape = GlassShapes.chip,
                            onClickLabel = option.label,
                            onClick = { onSelect(option) },
                        )
                        .semantics { selected = isSelected }
                        .heightIn(min = 72.dp)
                        .flatGlass(
                            GlassShapes.chip,
                            if (isSelected) accent.container else palette.card2,
                            if (isSelected) accent.border else palette.border,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconPreview(option, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(
                            option.label,
                            style = if (isSelected) AppTypography.body.strong else AppTypography.body.medium,
                            color = if (isSelected) accent.accent else palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        androidx.compose.material3.Text(
                            option.description,
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.size(MinTouchTarget), contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(accent.accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = accent.onAccent,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal expect fun AppIconPreview(
    variant: AppIconVariant,
    modifier: Modifier = Modifier,
)
