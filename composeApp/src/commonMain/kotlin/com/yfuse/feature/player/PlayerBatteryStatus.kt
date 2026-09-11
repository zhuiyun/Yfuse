package com.yfuse.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ThemeText

internal data class PlayerBattery(
    val percent: Int,
    val charging: Boolean,
)

internal fun playerBatteryPercent(
    level: Int,
    scale: Int,
): Int? = if (level < 0 || scale <= 0) null else (level.toLong() * 100 / scale).coerceIn(0L, 100L).toInt()

@Composable
internal expect fun rememberPlayerBattery(): State<PlayerBattery?>

/** Lives inside the same ChromeVisibility as the top controls; no receiver survives its dismissal. */
@Composable
internal fun PlayerBatteryStatus() {
    val battery by rememberPlayerBattery()
    val current = battery ?: return
    val color = Color.White.copy(alpha = 0.82f)
    Row(
        Modifier.clearAndSetSemantics {
            contentDescription = "电量 ${current.percent}%" + if (current.charging) "，正在充电" else ""
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(21.dp, 11.dp)) {
            val stroke = 1.dp.toPx()
            val bodyWidth = size.width - 3.dp.toPx()
            drawRoundRect(
                color,
                Offset(stroke / 2, stroke / 2),
                Size(bodyWidth - stroke, size.height - stroke),
                CornerRadius(2.dp.toPx()),
                style = Stroke(stroke),
            )
            drawRoundRect(
                color,
                Offset(bodyWidth + 1.dp.toPx(), size.height * 0.3f),
                Size(2.dp.toPx(), size.height * 0.4f),
                CornerRadius(0.6.dp.toPx()),
            )
            val inset = 2.5.dp.toPx()
            drawRoundRect(
                color,
                Offset(inset, inset),
                Size((bodyWidth - inset * 2) * current.percent / 100f, size.height - inset * 2),
                CornerRadius(0.6.dp.toPx()),
            )
        }
        if (current.charging) {
            Canvas(Modifier.size(7.dp, 11.dp)) {
                val bolt =
                    Path().apply {
                        moveTo(size.width * 0.65f, 0f)
                        lineTo(0f, size.height * 0.58f)
                        lineTo(size.width * 0.42f, size.height * 0.58f)
                        lineTo(size.width * 0.3f, size.height)
                        lineTo(size.width, size.height * 0.38f)
                        lineTo(size.width * 0.57f, size.height * 0.38f)
                        close()
                    }
                drawPath(bolt, color)
            }
        }
        ThemeText(
            "${current.percent}%",
            style = AppTypography.caption.strong,
            color = color,
            maxLines = 1,
        )
    }
}
