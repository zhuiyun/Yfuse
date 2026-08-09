package com.yfuse.core.designsystem

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

enum class YfButtonTone {
    Primary,
    Secondary,
    Destructive,
}

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
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.52f }
            .flatGlass(
                shape = GlassShapes.card,
                fill = palette.card2,
                border = palette.border,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = mr(10f, 600),
            color = if (value.isEmpty()) palette.sub else palette.sub2,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                textStyle = sc(13f, 550).copy(color = palette.text),
                cursorBrush = SolidColor(LocalAccent.current.color),
            )
            if (value.isEmpty()) {
                Text(" ", style = sc(13f, 550), color = Color.Transparent)
            }
        }
    }
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
    val accent = LocalAccent.current.color
    val fill = when (tone) {
        YfButtonTone.Primary -> accent.copy(alpha = if (palette.isDark) 0.48f else 0.18f)
        YfButtonTone.Secondary -> palette.card2
        YfButtonTone.Destructive -> Brand.Danger.copy(alpha = if (palette.isDark) 0.24f else 0.10f)
    }
    val border = when (tone) {
        YfButtonTone.Primary -> accent.copy(alpha = 0.42f)
        YfButtonTone.Secondary -> palette.border
        YfButtonTone.Destructive -> Brand.Danger.copy(alpha = 0.34f)
    }
    val content = when (tone) {
        YfButtonTone.Primary -> if (palette.isDark) Color.White else accent
        YfButtonTone.Secondary -> palette.text
        YfButtonTone.Destructive -> Brand.Danger
    }

    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.44f }
            .pressable(
                enabled = enabled && !loading,
                haptic = HapticSignal.Confirm.takeIf { tone != YfButtonTone.Secondary },
                onClick = onClick,
            )
            .liquidGlass(
                shape = GlassShapes.card,
                fill = fill,
                border = border,
                over = palette.background,
                sheen = if (tone == YfButtonTone.Secondary) 0.55f else 0.78f,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
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
        Text(label, style = sc(12.5f, 700), color = content)
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
    Text(
        text = label,
        style = sc(11.5f, 650),
        color = if (destructive) Brand.Danger else LocalAccent.current.color,
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}
