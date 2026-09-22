import re
from edit import read, write, replace
base = 'composeApp/src/commonMain/kotlin/com/yfuse/'

p = base + 'core/designsystem/Glass.kt'
s = read(p)
start = s.index('    // The theme is the wrong signal here', s.index('fun Modifier.liquidGlass('))
end = s.index('    return this', start)
paint = s[start:end]
s = s[:start] + '    val (body, gloss) = liquidGlassBrushes(fill, over, sheen)\n' + s[end:]
s += '''
/** Shared paint math for fixed and animated material; geometry never depends on the frame colour. */
private fun liquidGlassBrushes(fill: Color, over: Color, sheen: Float): Pair<Brush, Brush> {
''' + paint + '''    return body to gloss
}

/** Animated material inputs are read during drawing, not by the enclosing screen composition. */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    fill: () -> Color,
    border: () -> Color?,
    over: () -> Color,
    sheen: Float = 1f,
): Modifier {
    val palette = LocalPalette.current
    val reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency
    val muted = LocalMutedGlass.current
    val frosted = frostedGlass()
    return clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(Dimens.hairline.toPx() * 2f)
        val mutedStroke = Stroke(1.dp.toPx())
        onDrawBehind {
            val body = fill()
            val requestedBorder = border()
            val edge = resolveGlassMaterialBorder(requestedBorder, palette)
            when {
                muted -> {
                    val neutral = if (palette.isDark) Color(0xFF353B45) else Color(0xFFBEC3CB)
                    drawOutline(outline, if (reduceTransparency) neutral else body.copy(alpha = body.alpha.coerceAtMost(0.10f)))
                    if (requestedBorder != null) drawOutline(outline, Color.White.copy(alpha = 0.10f), style = mutedStroke)
                }
                reduceTransparency -> {
                    drawOutline(outline, reducedTransparencyFill(body, palette, over()))
                    reducedTransparencyBorder(edge, palette)?.let { drawOutline(outline, it, style = stroke) }
                }
                frosted -> {
                    if (body.alpha > 0.001f || edge != null) {
                        drawOutline(outline, frostedSurfaceBrush(body, palette, GlassSurfaceWeight.Strong.frostDensity))
                        frostedMaterialBorder(edge, palette)?.let { drawOutline(outline, it, style = stroke) }
                    }
                }
                else -> {
                    val (ramp, gloss) = liquidGlassBrushes(body, over(), sheen)
                    drawOutline(outline, ramp)
                    drawOutline(outline, gloss)
                    if (edge != null) drawOutline(outline, edge, style = stroke)
                }
            }
        }
    }
}
'''
write(p, s)

p = base + 'feature/detail/DetailHero.kt'
s = read(p)
imports = '''import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
'''
s = s.replace('import androidx.compose.foundation.background', imports + 'import androidx.compose.foundation.background')
start = s.index('            val p = progress.value', s.index('internal fun DetailTopBar('))
end = s.index('            Text(', start)
s = s[:start] + '''            DetailTopBarIcon(
                icon = AppIcons.ChevronLeft,
                description = "返回",
                progress = progress,
                surfaceColor = surfaceColor,
                onClick = onBack,
            )
''' + s[end:]
start = s.index('                Icon(\n                    AppIcons.More,', s.index('internal fun DetailTopBar('))
end = s.index('\n            }\n        }\n    }\n}', start)
s = s[:start] + '''                DetailTopBarIcon(
                    icon = AppIcons.More,
                    description = "更多操作",
                    progress = progress,
                    surfaceColor = surfaceColor,
                    onClick = onMore,
                )''' + s[end:]
pos = s.index('// ---------------------------------------------------------------- information sheet')
s = s[:pos] + '''/** Both glyph tint and glass colour follow scroll solely in the draw phase. */
@Composable
private fun DetailTopBarIcon(
    icon: ImageVector,
    description: String,
    progress: State<Float>,
    surfaceColor: Color,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val painter = rememberVectorPainter(icon)
    Canvas(
        Modifier
            .pressable(onClick = onClick)
            .touchTarget()
            .size(38.dp)
            .liquidGlass(
                shape = CircleShape,
                fill = { lerp(Color(0xFF11151F).copy(alpha = 0.28f), palette.card2, progress.value) },
                border = { lerp(Color.White.copy(alpha = 0.34f), palette.border, progress.value) },
                over = { lerp(HeroInk, surfaceColor, progress.value) },
                sheen = 0.7f,
            )
            .padding(11.dp)
            .semantics { contentDescription = description },
    ) {
        with(painter) {
            draw(size, colorFilter = ColorFilter.tint(lerp(Color.White, palette.text, progress.value)))
        }
    }
}

''' + s[pos:]
write(p, s)

# A reduce-motion change must finish an in-flight transition, even if its target is unchanged.
p = base + 'core/designsystem/ThemeCrossfade.kt'
replace(p, 'if (target == to) return', 'if (target == to && progress.value >= 1f) return')
