package com.yfuse.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp

/** Independent optical layers; source preset never gates an effect. No noise is added. */
internal fun Modifier.glassMaterialFinish(
    material: GlassMaterial,
    dark: Boolean,
    opaque: Boolean,
): Modifier {
    if (opaque || (material.prism == 0f && material.pearl == 0f && material.fluted == 0f)) return this
    return drawWithCache {
        val pearlAlpha = (if (dark) 0.18f else 0.42f) * material.pearl
        val sheen =
            if (material.prism > 0f) {
                Brush.linearGradient(
                    0f to Color(0xFFBCF9EB).copy(alpha = (if (dark) 0.10f else 0.18f) * material.prism),
                    0.18f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1f to Color(0xFFE0C1F4).copy(alpha = 0.18f * material.prism),
                )
            } else {
                null
            }
        val glow =
            if (material.pearl > 0f) {
                Brush.radialGradient(
                    listOf(Color(0xFFFFF1E6).copy(alpha = pearlAlpha), Color.Transparent),
                    center = Offset.Zero,
                    radius = size.maxDimension.coerceAtLeast(1f),
                )
            } else {
                null
            }
        val foot =
            if (material.pearl > 0f) {
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xFFA8C9DE).copy(alpha = 0.12f * material.pearl)),
                )
            } else {
                null
            }
        val ribs =
            if (material.fluted > 0f) {
                Brush.linearGradient(
                    0f to Color.Transparent,
                    0.16f to Color.White.copy(alpha = (if (dark) 0.12f else 0.26f) * material.fluted),
                    0.58f to Color(0xFF183748).copy(alpha = 0.14f * material.fluted),
                    1f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(material.fluteWidth.dp.toPx(), 0f),
                    tileMode = TileMode.Repeated,
                )
            } else {
                null
            }
        onDrawBehind {
            sheen?.let { drawRect(it) }
            glow?.let { drawRect(it) }
            foot?.let { drawRect(it) }
            ribs?.let { drawRect(it) }
        }
    }
}
