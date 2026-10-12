package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** The source's exact contour, carried by the overlay all the way back into the poster. */
internal class SharedArtworkClipShape(
    private val source: Shape,
    private val rounding: () -> Float,
) : Shape {
    private var lastSize = Size.Unspecified
    private var lastDirection = LayoutDirection.Ltr
    private var lastDensity = 0f
    private var lastFontScale = 0f
    private var lastRounding = Float.NaN
    private var lastOutline: Outline? = null

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val amount = rounding().coerceIn(0f, 1f)
        lastOutline?.let {
            if (lastSize == size &&
                lastDirection == layoutDirection &&
                lastDensity == density.density &&
                lastFontScale == density.fontScale &&
                lastRounding == amount
            ) {
                return it
            }
        }
        val outline =
            if (source is ContinuousRoundedCornerShape) {
                source.copy(radius = source.radius * amount).createOutline(size, layoutDirection, density)
            } else {
                when (val original = source.createOutline(size, layoutDirection, density)) {
                    is Outline.Rounded -> {
                        val rect = original.roundRect
                        Outline.Rounded(
                            RoundRect(
                                rect.left,
                                rect.top,
                                rect.right,
                                rect.bottom,
                                rect.topLeftCornerRadius.scaled(amount),
                                rect.topRightCornerRadius.scaled(amount),
                                rect.bottomRightCornerRadius.scaled(amount),
                                rect.bottomLeftCornerRadius.scaled(amount),
                            ),
                        )
                    }
                    else -> original
                }
            }
        lastSize = size
        lastDirection = layoutDirection
        lastDensity = density.density
        lastFontScale = density.fontScale
        lastRounding = amount
        lastOutline = outline
        return outline
    }
}

private fun CornerRadius.scaled(amount: Float): CornerRadius = CornerRadius(x * amount, y * amount)
