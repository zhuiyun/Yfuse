package com.yfuse.core.designsystem

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.52f }
            .flatGlass(
                shape = AppShapes.control,
                fill = palette.card2,
                border = if (focused) accent.border else palette.border,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics { contentDescription = label },
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        textStyle = AppTypography.body.medium.copy(color = palette.text),
        cursorBrush = SolidColor(accent.accent),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = label,
                    style = AppTypography.caption.strong,
                    color = if (focused) accent.accent else palette.sub2,
                )
                Box(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
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
    // Buttons stay physically neutral. Meaning lives in ink and the hairline edge, so primary
    // and destructive actions never turn into the large solid blue/red slabs this design rejects.
    val fill = when (tone) {
        YfButtonTone.Primary -> palette.glassStrong
        YfButtonTone.Secondary -> palette.card2
        YfButtonTone.Destructive -> palette.glassStrong
    }
    val border = when (tone) {
        YfButtonTone.Primary -> accent.border
        YfButtonTone.Secondary -> palette.border
        YfButtonTone.Destructive -> palette.error
    }
    val content = when (tone) {
        YfButtonTone.Primary -> accent.accent
        YfButtonTone.Secondary -> palette.text
        YfButtonTone.Destructive -> palette.error
    }

    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.44f }
            .pressable(
                enabled = enabled && !loading,
                haptic = HapticSignal.Confirm.takeIf { tone != YfButtonTone.Secondary },
                focusShape = AppShapes.control,
                onClickLabel = label,
                onClick = onClick,
            )
            .liquidGlass(
                shape = AppShapes.control,
                fill = fill,
                border = border,
                over = palette.background,
                sheen = if (tone == YfButtonTone.Secondary) 0.55f else 0.78f,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp)
            .semantics {
                if (loading) stateDescription = "处理中"
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = content,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = AppTypography.body.strong, color = content)
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
    val fill = if (destructive) palette.glassStrong else palette.card2
    val border = if (destructive) palette.error else palette.border
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
            )
            .liquidGlass(
                shape = AppShapes.control,
                fill = fill,
                border = border,
                over = palette.background,
                sheen = if (destructive) 0.74f else 0.62f,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
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
