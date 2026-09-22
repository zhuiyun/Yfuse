package com.yfuse.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A real modal material over a separate, static backdrop; never captures its own pane. */
@Composable
fun GlassMaterialPreview(
    dark: Boolean,
    materials: GlassMaterials,
    modifier: Modifier = Modifier,
    height: Dp = 232.dp,
) {
    val material = materials.forTheme(dark)
    val palette = material.contentPalette(if (dark) DarkPalette else LightPalette)
    val backdrop = rememberBackdropState()
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(AppShapes.sheet)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().backdropSource(backdrop)) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF284D75), Color(0xFF859BAC), Color(0xFFE4A884)),
                ),
            )
            drawCircle(Color(0xFFE8C49B), size.width * 0.29f, Offset(size.width * 0.82f, size.height * 0.25f))
            drawCircle(Color(0xFF6FAFA5), size.width * 0.40f, Offset(size.width * 0.14f, size.height * 0.90f))
            for (index in 0..7) {
                val x = size.width * index / 7f
                drawLine(
                    Color.White.copy(alpha = 0.20f),
                    Offset(x, 0f),
                    Offset(x - 80.dp.toPx(), size.height),
                    1.dp.toPx(),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(palette.scrim.copy(alpha = material.scrim)))
        CompositionLocalProvider(
            LocalGlassMaterials provides materials,
            LocalDialogBackdrop provides backdrop,
            LocalPalette provides palette,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 30.dp)
                    .fillMaxWidth()
                    .mutedGlassPanel(dark = dark)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemeText(material.preset.label, style = AppTypography.caption.strong, color = palette.text)
                ThemeText("光影，透过玻璃", style = AppTypography.section.strong, color = palette.text)
                ThemeText("观察背景的色彩、边缘与透光变化", style = AppTypography.caption.regular, color = palette.dialogBody)
            }
        }
    }
}
