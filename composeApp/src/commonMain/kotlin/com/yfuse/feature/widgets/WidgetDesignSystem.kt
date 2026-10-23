package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.controlSurface
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberGlassSelectionStyle
import com.yfuse.core.designsystem.rememberSegmentIndicator
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.ThemeText as Text

/** Shared surface and rhythm for the custom-home workspace and plugin flows. */
@Composable
internal fun WidgetSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints {
        val limit = maxHeight * .92f
        GlassDialog(
            onDismiss = onDismiss,
            modifier = Modifier.heightIn(max = limit).then(modifier).semantics { paneTitle = title },
            scrollable = false,
            contentPadding = 16.dp,
            alignment = Alignment.BottomCenter,
            windowPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            maxWidth = 600.dp,
            animation = DialogAnimation.Slide,
        ) {
            val close = overlayDismiss(onDismiss)
            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).background(LocalPalette.current.sub2.copy(alpha = .25f), CircleShape))
            }
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    title,
                    Modifier.padding(horizontal = 48.dp),
                    style = AppTypography.body.strong.copy(fontSize = 17.sp),
                    color = LocalPalette.current.text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.align(Alignment.CenterEnd)) { WidgetIconButton(AppIcons.Close, "关闭$title", close) }
            }
            content()
        }
    }
}

@Composable
internal fun WidgetGroupCaption(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = AppTypography.body.regular.copy(fontSize = 12.sp),
        color = LocalPalette.current.sub2,
    )
}

@Composable
internal fun WidgetMenuRow(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = LocalPalette.current
    val ink = if (destructive) palette.error else palette.text
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .alpha(if (enabled) 1f else .45f)
            .semantics {
                this.selected =
                    selected
            }.pressable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon !=
            null
        ) {
            ThemeIcon(
                icon,
                null,
                tint = if (destructive) ink else LocalAccentColors.current.accent,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                color = ink,
                style = AppTypography.body.regular.copy(fontSize = 15.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = palette.sub2,
                    style = AppTypography.body.regular.copy(fontSize = 12.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                value,
                Modifier.widthIn(max = 132.dp),
                color = palette.sub2,
                style = AppTypography.body.regular,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        ThemeIcon(
            if (selected) AppIcons.Check else AppIcons.ChevronRight,
            null,
            tint = if (selected) LocalAccentColors.current.accent else palette.sub2,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
internal fun WidgetSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val selection = rememberGlassSelectionStyle()
    val indicator = rememberSegmentIndicator(selectedIndex, Color.Transparent, selectionStyle = selection)
    Row(
        modifier
            .selectableGroup()
            .glass(CircleShape, palette.controlSurface, palette.border)
            .padding(4.dp)
            .then(indicator.container),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val active = selectedIndex == index
            Box(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = MinTouchTarget)
                    .then(indicator.item(index))
                    .semantics {
                        selected = active
                    }.pressable(role = Role.Tab, onClick = {
                        onSelect(index)
                    })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style =
                        AppTypography.body.regular.copy(
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    color = selectionColor(if (active) selection.contentColor else palette.sub2),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun WidgetEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).glass(RoundedCornerShape(22.dp), palette.card, palette.border),
            contentAlignment = Alignment.Center,
        ) {
            ThemeIcon(icon, null, tint = LocalAccentColors.current.accent, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = AppTypography.section.strong.copy(fontSize = 18.sp),
            color = palette.text,
            textAlign = TextAlign.Center,
        )
        Text(
            description,
            Modifier.padding(top = 8.dp, bottom = 20.dp),
            style = AppTypography.body.regular.copy(fontSize = 13.sp, lineHeight = 21.sp),
            color = palette.sub2,
            textAlign = TextAlign.Center,
        )
        if (action != null) WidgetPrimaryButton(onAction) { Text(action) }
    }
}
