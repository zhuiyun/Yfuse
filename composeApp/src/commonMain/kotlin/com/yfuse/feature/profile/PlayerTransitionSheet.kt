package com.yfuse.feature.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.PlayerTransitionStyle
import com.yfuse.core.designsystem.overlayDismiss
import kotlin.math.PI
import kotlin.math.sin
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun PlayerTransitionSheet(
    selected: PlayerTransitionStyle,
    onSelect: (PlayerTransitionStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            "播放器进出场",
            "${PlayerTransitionStyle.entries.size} 套开合方式，从播放键或大图打开播放器、关闭时回到原处",
            onClose = onDismiss,
        )
        if (LocalAccessibilityOptions.current.reduceMotion) {
            Text(
                "已开启“减少动画”，播放器统一使用淡入淡出。",
                color = LocalPalette.current.sub,
                style = AppTypography.caption.regular,
            )
            Spacer(Modifier.height(OverlayOptionSpacing))
        }
        PlayerTransitionStyle.entries.forEach { style ->
            OverlayOptionRow(
                label = style.label,
                description = style.description,
                selected = selected == style,
                onClick = { onSelect(style) },
                leadingContent = { PlayerTransitionGlyph(style) },
            )
            Spacer(Modifier.height(OverlayOptionSpacing))
        }
        OverlayButton("完成", onClick = overlayDismiss(onDismiss))
    }
}

/** A still of each set's idea: a picture turning, a slit of light, a glass bar, a zoom, a wave, a bloom. */
@Composable
private fun PlayerTransitionGlyph(style: PlayerTransitionStyle) {
    val ink = LocalPalette.current.text
    val accent = LocalAccentColors.current.accent
    Canvas(Modifier.size(36.dp).clearAndSetSemantics {}) {
        when (style) {
            PlayerTransitionStyle.Turn -> turnGlyph(ink, accent)
            PlayerTransitionStyle.Curtain -> curtainGlyph(ink)
            PlayerTransitionStyle.Glass -> glassGlyph(ink, accent)
            PlayerTransitionStyle.PushIn -> pushGlyph(ink, accent)
            PlayerTransitionStyle.Tide -> tideGlyph(ink, accent)
            PlayerTransitionStyle.Defocus -> defocusGlyph(accent)
        }
    }
}

private fun DrawScope.frameOutline(
    ink: Color,
    width: Float,
    height: Float,
    alpha: Float = 0.9f,
) {
    drawRoundRect(
        ink.copy(alpha = alpha),
        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(3.dp.toPx()),
        style = Stroke(1.6.dp.toPx()),
    )
}

private fun DrawScope.turnGlyph(
    ink: Color,
    accent: Color,
) {
    frameOutline(ink.copy(alpha = 0.35f), 14.dp.toPx(), 22.dp.toPx())
    rotate(-28f) {
        drawRoundRect(
            accent,
            topLeft = Offset(center.x - 13.dp.toPx(), center.y - 7.dp.toPx()),
            size = Size(26.dp.toPx(), 14.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}

private fun DrawScope.curtainGlyph(ink: Color) {
    frameOutline(ink.copy(alpha = 0.5f), 30.dp.toPx(), 18.dp.toPx())
    val glow = 6.dp.toPx()
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.5f to Color(0xFFFFB066).copy(alpha = 0.8f),
            1f to Color.Transparent,
            startY = center.y - glow,
            endY = center.y + glow,
        ),
        topLeft = Offset(center.x - 13.dp.toPx(), center.y - glow),
        size = Size(26.dp.toPx(), glow * 2f),
    )
    drawRect(
        Color(0xFFFFF4E2),
        topLeft = Offset(center.x - 13.dp.toPx(), center.y - 0.8.dp.toPx()),
        size = Size(26.dp.toPx(), 1.6.dp.toPx()),
    )
}

private fun DrawScope.glassGlyph(
    ink: Color,
    accent: Color,
) {
    val width = 30.dp.toPx()
    val height = 12.dp.toPx()
    drawRoundRect(
        Brush.verticalGradient(
            listOf(
                Color(0xFF78B0FF).copy(alpha = 0.55f),
                accent.copy(alpha = 0.3f),
                Color(0xFFFFA054).copy(alpha = 0.5f),
            ),
        ),
        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
    )
    drawRoundRect(
        ink.copy(alpha = 0.75f),
        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
        style = Stroke(1.2.dp.toPx()),
    )
    val play =
        Path().apply {
            moveTo(center.x - 2.dp.toPx(), center.y - 3.5.dp.toPx())
            lineTo(center.x + 3.5.dp.toPx(), center.y)
            lineTo(center.x - 2.dp.toPx(), center.y + 3.5.dp.toPx())
            close()
        }
    drawPath(play, ink)
}

private fun DrawScope.pushGlyph(
    ink: Color,
    accent: Color,
) {
    frameOutline(ink.copy(alpha = 0.3f), 30.dp.toPx(), 30.dp.toPx())
    frameOutline(ink.copy(alpha = 0.55f), 20.dp.toPx(), 20.dp.toPx())
    drawRoundRect(
        accent,
        topLeft = Offset(center.x - 5.dp.toPx(), center.y - 5.dp.toPx()),
        size = Size(10.dp.toPx(), 10.dp.toPx()),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
}

private fun DrawScope.tideGlyph(
    ink: Color,
    accent: Color,
) {
    for (row in 0..2) {
        val y = center.y + (row - 1) * 7.dp.toPx()
        val path = Path()
        val left = center.x - 14.dp.toPx()
        val width = 28.dp.toPx()
        val steps = 24
        for (step in 0..steps) {
            val x = left + width * step / steps
            val phase = step.toFloat() / steps * 2f * PI.toFloat() + row * 0.9f
            val wave = sin(phase) * 2.2.dp.toPx() * (1f - row * 0.25f)
            if (step == 0) path.moveTo(x, y + wave) else path.lineTo(x, y + wave)
        }
        drawPath(path, if (row == 1) accent else ink.copy(alpha = 0.45f), style = Stroke(1.6.dp.toPx()))
    }
}

private fun DrawScope.defocusGlyph(accent: Color) {
    drawCircle(
        Brush.radialGradient(
            0f to accent.copy(alpha = 0.95f),
            0.45f to accent.copy(alpha = 0.45f),
            1f to Color.Transparent,
            center = center,
            radius = 16.dp.toPx(),
        ),
        radius = 16.dp.toPx(),
    )
    drawCircle(Color.White.copy(alpha = 0.9f), radius = 3.dp.toPx())
}
