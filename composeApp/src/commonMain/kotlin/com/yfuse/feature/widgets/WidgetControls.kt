package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassButtonEmphasis
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalMutedGlass
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PillSwitch
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.controlBorder
import com.yfuse.core.designsystem.controlSurface
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.glassControlHighlight
import com.yfuse.core.designsystem.glassSelection
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.readableSurfaceInk
import com.yfuse.core.designsystem.rememberGlassSelectionStyle
import com.yfuse.core.designsystem.resolveGlassButtonVisuals

@Composable
internal fun WidgetIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) = WidgetIconButton(icon, label, enabled = true, onClick = onClick)

@Composable
internal fun WidgetIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.44f)
            .pressable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).glass(CircleShape, LocalPalette.current.card, LocalPalette.current.border),
            contentAlignment = Alignment.Center,
        ) {
            ThemeIcon(
                icon,
                contentDescription = label,
                tint = LocalAccentColors.current.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun WidgetAction(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    emphasis: GlassButtonEmphasis?,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val raisedPrimary = emphasis == GlassButtonEmphasis.Primary && LocalMutedGlass.current
    val plate = if (palette.isDark) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.84f)
    val underlay =
        if (LocalAccessibilityOptions.current.reduceTransparency) {
            plate.compositeOver(
                palette.background,
            )
        } else {
            plate
        }
    val visuals =
        emphasis?.let { resolveGlassButtonVisuals(it, palette, accent) }?.let {
            if (raisedPrimary) {
                it.copy(
                    fill = plate,
                    border = glassControlHighlight(accent, palette.isDark),
                    content = readableSurfaceInk(accent.accent, plate.compositeOver(palette.background)),
                )
            } else {
                it
            }
        }
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.44f)
            .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
            // Muted modal controls cap their body at 12%; this local plate keeps the primary action distinct.
            .then(if (raisedPrimary) Modifier.background(underlay, CircleShape) else Modifier)
            .then(
                if (visuals ==
                    null
                ) {
                    Modifier
                } else {
                    Modifier.liquidGlass(CircleShape, visuals.fill, visuals.border, sheen = visuals.sheen)
                },
            ).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides (visuals?.content ?: accent.accent),
            LocalTextStyle provides AppTypography.body.strong.copy(fontSize = 14.sp),
        ) { content() }
    }
}

@Composable
internal fun WidgetPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = WidgetAction(onClick, modifier, enabled, GlassButtonEmphasis.Primary, content)

@Composable
internal fun WidgetSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = WidgetAction(onClick, modifier, enabled, GlassButtonEmphasis.Neutral, content)

@Composable
internal fun WidgetTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = WidgetAction(onClick, modifier, enabled, null, content)

@Composable
internal fun WidgetFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val selection = rememberGlassSelectionStyle()
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .semantics { this.selected = selected }
            .pressable(role = Role.RadioButton, onClick = onClick)
            .then(
                if (selected) {
                    Modifier.glassSelection(AppShapes.chip, selection)
                } else {
                    Modifier.flatGlass(AppShapes.chip, palette.controlSurface, palette.controlBorder)
                },
            ).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) selection.contentColor else palette.text,
            LocalTextStyle provides if (selected) AppTypography.body.strong else AppTypography.body.medium,
        ) { label() }
    }
}

@Composable
internal fun WidgetSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { toggleableState = ToggleableState(checked) }
            .pressable(role = Role.Switch, onClick = { onCheckedChange(!checked) }),
        contentAlignment = Alignment.Center,
    ) {
        PillSwitch(checked)
    }
}

@Composable
internal fun WidgetAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    WidgetSheet(title = "提示", onDismiss = onDismissRequest) {
        title()
        text()
        confirmButton()
    }
}

@Composable
internal fun WidgetProgress(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    if (progress == null) {
        androidx.compose.material3.LinearProgressIndicator(
            modifier = modifier,
            color = accent.accent,
            trackColor = palette.controlSurface,
        )
    } else {
        Box(
            modifier
                .height(
                    4.dp,
                ).clip(
                    AppShapes.track,
                ).background(palette.controlSurface)
                .border(1.dp, palette.controlBorder, AppShapes.track),
        ) {
            Box(Modifier.fillMaxWidth(progress().coerceIn(0f, 1f)).height(4.dp).background(accent.accent))
        }
    }
}
