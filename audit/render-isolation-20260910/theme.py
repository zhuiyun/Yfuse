from edit import ROOT,read,write
C='composeApp/src/commonMain/kotlin/com/yfuse/'
p=C+'core/designsystem/Theme.kt';s=read(p)
start=s.index('    val animatedColors =');end=s.index('    val density =',start)
s=s[:start]+'    val colors = remember(targetPalette, targetAccent) { ThemeColors(targetPalette, targetAccent) }\n'+s[end:]
s=s.replace('AnimatedThemeColors(dark, animatedColors)', 'TargetThemeColors(dark, colors)')
s=s.replace('/** Animation reads live below density/accessibility/glass providers and notify colour readers only. */','/** Publish targets once. Finite colour motion is owned by the consuming text/material node. */')
s=s.replace('private fun AnimatedThemeColors(', 'private fun TargetThemeColors(').replace('colors: State<ThemeColors>', 'colors: ThemeColors').replace('val shown = colors.value','val shown = colors')
s=s.replace('LocalPalette provides shown.palette,','LocalThemeColorTarget provides colors,\n        LocalPalette provides shown.palette,')
write(p,s)

write(C+'core/designsystem/ThemeColorConsumer.kt','''package com.yfuse.core.designsystem

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.isSpecified

internal val LocalThemeColorTarget = compositionLocalOf<ThemeColors?> { null }

private class ThemeConsumerMemory(var theme: ThemeColors?, var color: Color)

/** Ordinary status/artwork animations pass through. Only a theme target change starts this clock. */
@Composable
internal fun rememberThemeConsumerColor(target: Color): State<Color> {
    val theme = LocalThemeColorTarget.current
    val reduced = LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current
    val memory = remember { ThemeConsumerMemory(theme, target) }
    val animation = remember(theme) {
        if (theme != memory.theme && memory.color.isSpecified && target.isSpecified && memory.color != target && !reduced)
            Animatable(memory.color) else null
    }
    var finished by remember(animation) { mutableStateOf(animation == null) }
    SideEffect { memory.theme = theme; memory.color = target }
    LaunchedEffect(animation, target, reduced) {
        if (animation != null && !finished) {
            if (reduced || !target.isSpecified) animation.snapTo(if (target.isSpecified) target else animation.value)
            else animation.animateTo(target, tween(THEME_CROSSFADE_MS, easing = Motion.Curve))
            finished = true
        }
    }
    return if (!finished && animation != null) animation.asState() else rememberUpdatedState(target)
}

@Composable
internal fun Modifier.themeBackground(color: Color, shape: Shape = RectangleShape): Modifier {
    val shown = rememberThemeConsumerColor(color)
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { drawOutline(outline, shown.value) }
    }
}
''')

# Source-compatible wrappers put Text/Icon's Color-only API behind its own restart boundary.
header='''package com.yfuse.core.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.text.InlineTextContent

'''
body=''
for typ in ['String','AnnotatedString']:
    extra='    inlineContent: Map<String, InlineTextContent> = mapOf(),\n' if typ=='AnnotatedString' else ''
    forward='inlineContent = inlineContent, ' if extra else ''
    body+='''@Composable
internal fun ThemeText(
    text: '''+typ+''', modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
'''+extra+'''    onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current,
) {
    val target = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val shown = rememberThemeConsumerColor(target)
    androidx.compose.material3.Text(
        text = text, modifier = modifier, color = shown.value, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
        textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
        overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
        '''+forward+'''onTextLayout = onTextLayout, style = style,
    )
}

'''
for param,typ in [('imageVector','ImageVector'),('painter','Painter'),('bitmap','ImageBitmap')]:
    body+='''@Composable
internal fun ThemeIcon('''+param+': '+typ+''', contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    val shown = rememberThemeConsumerColor(tint)
    androidx.compose.material3.Icon('''+param+' = '+param+''', contentDescription = contentDescription, modifier = modifier, tint = shown.value)
}

'''
write(C+'core/designsystem/ThemeColorContent.kt',header+body)

for folder in [ROOT/'composeApp/src/commonMain/kotlin/com/yfuse',ROOT/'composeApp/src/androidMain/kotlin/com/yfuse/feature']:
    for file in folder.rglob('*.kt'):
        rel=file.relative_to(ROOT).as_posix()
        s=read(rel)
        out=s.replace('import androidx.compose.material3.Text\n','import com.yfuse.core.designsystem.ThemeText as Text\n').replace('import androidx.compose.material3.Icon\n','import com.yfuse.core.designsystem.ThemeIcon as Icon\n')
        if out!=s:write(rel,out)

# Glass paints consume their clocks in drawing, never in the caller's composition.
p=C+'core/designsystem/Glass.kt';s=read(p)
start=s.index('    val surface =\n',s.index('private fun Modifier.glassMaterial'));end=s.index('\n}\n',start)
s=s[:start]+'''    val animatedFill = rememberThemeConsumerColor(fill)
    val animatedBorder = rememberThemeConsumerColor(resolvedBorder ?: Color.Transparent)
    return clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(Dimens.hairline.toPx() * 2f)
        onDrawBehind {
            val color = animatedFill.value
            val surface = when {
                accessibility.reduceTransparency -> {
                    val opaque = reducedTransparencyFill(color, palette)
                    Brush.linearGradient(listOf(opaque, opaque))
                }
                frosted -> frostedSurfaceBrush(color, palette, weight.frostDensity)
                else -> liquidSurfaceBrush(color, palette, weight)
            }
            drawOutline(outline, brush = surface)
            if (resolvedBorder != null) drawOutline(outline, animatedBorder.value, style = stroke)
        }
    }'''+s[end:]
start=s.index('    val palette = LocalPalette.current',s.index('fun Modifier.liquidGlass('));end=s.index('\n}\n',start)
s=s[:start]+'''    val animatedFill = rememberThemeConsumerColor(fill)
    val animatedBorder = rememberThemeConsumerColor(border ?: Color.Transparent)
    val animatedOver = rememberThemeConsumerColor(over)
    return liquidGlass(shape, { animatedFill.value }, { if (border != null) animatedBorder.value else null },
        { animatedOver.value }, sheen)
'''+s[end:]
write(p,s)
# App/page solid backgrounds. No global background alias: non-composable drawing helpers must stay pure.
for name in ['app/App.kt','feature/profile/ProfileScreen.kt','feature/search/SearchScreen.kt','feature/servers/ServersTabScreen.kt']:
    p=C+name;s=read(p)
    out=s.replace('.background(palette.background)', '.themeBackground(palette.background)')
    if out!=s:
        out=out.replace('\n\nimport ', '\n\nimport com.yfuse.core.designsystem.themeBackground\nimport ',1)
        write(p,out)
