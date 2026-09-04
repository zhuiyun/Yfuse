package com.yfuse.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

enum class YfButtonTone {
    Primary,
    Secondary,
    Destructive,
}

/** A compact labelled field on the same glass, type and emphasis system as the rest of Yfuse. */
@Composable
fun YfFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(),
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: ImageVector? = null,
    trailingIconContentDescription: String? = null,
    onTrailingIconClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.52f }
                .flatGlass(
                    shape = AppShapes.control,
                    fill = palette.card2,
                    border = palette.border,
                ).then(
                    if (focused) {
                        Modifier.border(2.dp, accent.border, AppShapes.control)
                    } else {
                        Modifier
                    },
                ).padding(
                    horizontal = 14.dp,
                    vertical = if (trailingIcon == null) 9.dp else 4.dp,
                ).semantics { contentDescription = label },
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        textStyle = AppTypography.body.medium.copy(color = palette.text),
        cursorBrush = SolidColor(accent.accent),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = label,
                        style = AppTypography.caption.strong,
                        color = if (focused) accent.accent else palette.sub2,
                    )
                    Box(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = AppTypography.body.regular,
                                color = palette.hint,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                }
                trailingIcon?.let { icon ->
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .then(
                                    if (onTrailingIconClick != null) {
                                        Modifier
                                            .pressable(
                                                enabled = enabled,
                                                onClickLabel = trailingIconContentDescription,
                                                onClick = onTrailingIconClick,
                                            ).touchTarget()
                                    } else {
                                        Modifier
                                    },
                                ).size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription =
                                trailingIconContentDescription.takeIf { onTrailingIconClick == null },
                            tint = if (focused) accent.accent else palette.sub2,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun YfButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tone: YfButtonTone = YfButtonTone.Primary,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val emphasis =
        when (tone) {
            YfButtonTone.Primary -> GlassButtonEmphasis.Primary
            YfButtonTone.Secondary -> GlassButtonEmphasis.Neutral
            YfButtonTone.Destructive -> GlassButtonEmphasis.Destructive
        }
    val visuals = resolveGlassButtonVisuals(emphasis, palette, accent)

    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { alpha = glassButtonAlpha(enabled) }
            .pressable(
                enabled = enabled && !loading,
                haptic = HapticSignal.Confirm.takeIf { tone != YfButtonTone.Secondary },
                focusShape = AppShapes.control,
                onClickLabel = label,
                onClick = onClick,
            ).liquidGlass(
                shape = AppShapes.control,
                fill = visuals.fill,
                border = visuals.border,
                over = palette.background,
                sheen = visuals.sheen,
            ).padding(horizontal = 16.dp, vertical = 11.dp)
            .semantics {
                if (loading) stateDescription = "处理中"
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = visuals.content,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = AppTypography.body.strong, color = visuals.content)
    }
}

/** A low-emphasis text action for mode switches below a dominant form action. */
@Composable
fun YfInlineLinkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = LocalAccentColors.current
    Row(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .pressable(
                enabled = enabled,
                haptic = HapticSignal.Select,
                onClickLabel = label,
                onClick = onClick,
            ).touchTarget()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTypography.body.medium,
            color = accent.accent,
        )
    }
}

@Composable
fun YfLinkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val fill = if (destructive) palette.errorContainer.copy(alpha = 0.52f) else palette.card2
    val border = if (destructive) null else palette.border
    val content = if (destructive) palette.error else accent.accent
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .pressable(
                enabled = enabled,
                haptic = HapticSignal.Confirm.takeIf { destructive },
                focusShape = AppShapes.control,
                onClickLabel = label,
                onClick = onClick,
            ).liquidGlass(
                shape = AppShapes.control,
                fill = fill,
                border = border,
                over = palette.background,
                sheen = if (destructive) 0.74f else 0.62f,
            ).padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTypography.body.strong,
            color = content,
        )
    }
}
