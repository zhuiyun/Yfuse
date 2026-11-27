package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.collectVisiblePressAsState
import kotlin.math.PI
import kotlin.math.atan2

/** Shared crisp drop shadow so scrim-free player text stays legible over bright video frames. */
internal val PlayerVideoTextShadow =
    Shadow(
        color = Color.Black.copy(alpha = 0.78f),
        offset = Offset(0f, 1f),
        blurRadius = 4f,
    )

/** The touch target includes padding around the visible ring. Light the rim nearest the finger. */
@Composable
internal fun Modifier.playerControlRim(source: InteractionSource): Modifier {
    val options = LocalAccessibilityOptions.current
    val backing =
        background(
            Color.Black.copy(alpha = if (options.reduceTransparency) 0.56f else 0.34f),
            CircleShape,
        )
    if (options.reduceTransparency) return backing.border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
    val sheen =
        remember { Brush.linearGradient(listOf(Color.White.copy(alpha = 0.76f), Color.White.copy(alpha = 0.36f))) }
    val rim = backing.border(1.dp, sheen, CircleShape)
    if (options.reduceMotion || calmMotion()) return rim
    val pressed = source.collectVisiblePressAsState()
    var point by remember(source) { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Press) point = it.pressPosition }
    }
    return rim.drawWithContent {
        drawContent()
        if (!pressed.value) return@drawWithContent
        val delta =
            if (point.isSpecified) {
                point - center -
                    Offset(ControlTouchPadding.toPx(), ControlTouchPadding.toPx())
            } else {
                Offset.Zero
            }
        val angle = if (delta.getDistance() < 1.dp.toPx()) -90f else (atan2(delta.y, delta.x) * 180f / PI.toFloat())
        val stroke = 1.dp.toPx()
        drawArc(
            Color.White,
            angle - 34f,
            68f,
            false,
            Offset(stroke / 2f, stroke / 2f),
            Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}
