package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer

/** Large surfaces need less travel than small controls. These values never change hit targets. */
internal object PressFeedback {
    const val PRIMARY = 0.985f
    const val QUIET = 0.99f
}

/** Shares the actual click's interaction source; selection never creates another click handler. */
@Composable
internal fun Modifier.softSelectionSurface(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    selected: Boolean = false,
    selectedColor: Color = Color.Transparent,
    pressedColor: Color = LocalPalette.current.text.copy(alpha = 0.08f),
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current
    val selection =
        animateFloatAsState(
            if (selected) 1f else 0f,
            Motion.settle(reduceMotion),
            label = "softSelection",
        )
    val pressure =
        animateFloatAsState(
            if (pressed && enabled) 1f else 0f,
            Motion.pressSpec(pressed && enabled, reduceMotion),
            label = "softSurfacePressure",
        )
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val selectedAlpha = selection.value.coerceIn(0f, 1f) * selectedColor.alpha
            val pressedAlpha = pressure.value.coerceIn(0f, 1f) * pressedColor.alpha
            if (selectedAlpha > 0f) drawOutline(outline, selectedColor.copy(alpha = selectedAlpha))
            if (pressedAlpha > 0f) drawOutline(outline, pressedColor.copy(alpha = pressedAlpha))
        }
    }
}

/** Moves a compound action's material and contents together, retaining separate child actions. */
@Composable
internal fun Modifier.softActionSurface(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current
    val scale =
        animateFloatAsState(
            pressScaleTarget(reduceMotion, pressed && enabled, false, PressFeedback.PRIMARY),
            Motion.pressSpec(pressed && enabled, reduceMotion),
            label = "primarySurfacePressure",
        )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
