from edit import read,write
base='composeApp/src/commonMain/kotlin/com/yfuse/';ds=base+'core/designsystem/'
write(ds+'ContentHandoff.kt','''package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal enum class ContentPhase { Loading, Content, Empty, Error }
internal fun contentPhase(loading: Boolean, hasContent: Boolean, error: Boolean): ContentPhase = when {
    hasContent -> ContentPhase.Content
    loading -> ContentPhase.Loading
    error -> ContentPhase.Error
    else -> ContentPhase.Empty
}

/** A phase handoff keeps one content tree and fixed host bounds; refreshes retain visible data. */
@Composable
internal fun Modifier.contentHandoff(phase: Any): Modifier {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val committed = remember { arrayOf<Any>(phase) }
    val progress = remember(phase, moving) { Animatable(if (moving && committed[0] != phase) 0f else 1f) }
    SideEffect { committed[0] = phase }
    LaunchedEffect(progress) { if (progress.value < 1f) progress.animateTo(1f, tween(Motion.STATE_HANDOFF, easing = Motion.Curve)) }
    return graphicsLayer {
        alpha = progress.value
        translationY = 6.dp.toPx() * (1f - progress.value)
    }
}
''')
p=ds+'BackOverlay.kt';s=read(p)
s=s.replace('import androidx.compose.runtime.Composable','''import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable''')
s=s.replace('/** Full-screen app overlay that only needs a commit-time system back callback. */','/** Predictive return moves the overlay; cancellation restores the same content without dismissing it. */')
s=s.replace('    PlatformBackHandler(onBack = onBack)\n    Box(modifier.fillMaxSize(), content = content)', '''    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val currentBack by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    val settling = remember { arrayOfNulls<Job>(1) }
    PlatformPredictiveBackHandler(
        onProgress = {
            settling[0]?.cancel()
            progress = if (reduceMotion) 0f else it.coerceIn(0f, 1f)
        },
        onBack = { settling[0]?.cancel(); currentBack() },
        onCancel = {
            settling[0]?.cancel()
            settling[0] = scope.launch {
                if (reduceMotion) progress = 0f
                else animate(progress, 0f, animationSpec = Motion.settle()) { value, _ -> progress = value }
            }
        },
    )
    Box(modifier.fillMaxSize().graphicsLayer {
        val p = if (reduceMotion) 0f else progress.coerceIn(0f, 1f)
        scaleX = 1f - 0.06f * p
        scaleY = scaleX
        alpha = 1f - 0.2f * p
        translationX = 24.dp.toPx() * p
        shape = RoundedCornerShape(16.dp)
        clip = p > 0f
    }, content = content)''')
write(p,s)
p=ds+'OfficialNavDisplay.kt';s=read(p)
start=s.index('                predictivePopTransitionSpec = {');end=s.index('                entryProvider',start)
s=s[:start]+s[start:end].replace('popping = true,','popping = true,\n                        predictive = true,')+s[end:]
s=s.replace('    popping: Boolean,\n): ContentTransform {','    popping: Boolean,\n    predictive: Boolean = false,\n): ContentTransform {')
s=s.replace('        initialContentExit = transform.initialContentExit,','''        initialContentExit = transform.initialContentExit +
            if (predictive && motion == OfficialNavMotion.Stack) {
                scaleOut(tween(Motion.POP, easing = Motion.Curve), targetScale = 0.94f)
            } else ExitTransition.None,''')
s=s.replace('        ) togetherWith fadeOut(tween(Motion.QUICK, easing = Motion.Curve))','''        ) togetherWith (
            fadeOut(tween(Motion.QUICK, easing = Motion.Curve)) +
                slideOutHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) { -pushTravelPx / 2 }
        )''')
write(p,s)
write(ds+'SegmentIndicator.kt','''package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp

internal class SegmentIndicator(val container: Modifier, val item: (Int) -> Modifier)

/** Measured bounds support unequal labels and RTL; only drawing consumes the moving edges. */
@Composable
internal fun rememberSegmentIndicator(selectedIndex: Int, fill: Color, border: Color = Color.Transparent,
    underline: Boolean = false): SegmentIndicator {
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val target = bounds[selectedIndex]
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val left = animateFloatAsState(target?.left ?: 0f, Motion.settle(reduceMotion), label = "segmentLeft")
    val right = animateFloatAsState(target?.right ?: 0f, Motion.settle(reduceMotion), label = "segmentRight")
    return SegmentIndicator(
        Modifier.drawBehind {
            if (target != null) {
                val width = (right.value - left.value).coerceAtLeast(0f)
                if (underline) {
                    val line = 28.dp.toPx().coerceAtMost(width)
                    drawRoundRect(fill, Offset(left.value + (width-line)/2f, size.height-2.dp.toPx()),
                        Size(line,2.dp.toPx()), CornerRadius(1.dp.toPx()))
                } else if (width > 0f) {
                    translate(left = left.value) {
                        val outline = GlassShapes.chip.createOutline(Size(width, size.height), layoutDirection, this)
                        drawOutline(outline, fill)
                        drawOutline(outline, border, style = Stroke(1.dp.toPx()))
                    }
                }
            }
        },
        { index -> Modifier.onPlaced { coordinates ->
            val position = coordinates.positionInParent()
            bounds[index] = Rect(position, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        } },
    )
}

@Composable
internal fun selectionColor(target: Color): Color = rememberAnimatedColorState(target, Motion.QUICK).value
''')
p=base+'feature/profile/ProfileScreen.kt';s=read(p)
s=s.replace('import com.yfuse.core.designsystem.Motion\n','import com.yfuse.core.designsystem.Motion\nimport com.yfuse.core.designsystem.rememberSegmentIndicator\nimport com.yfuse.core.designsystem.selectionColor\n')
if 'import com.yfuse.core.designsystem.rememberSegmentIndicator' not in s:
 s=s.replace('import com.yfuse.core.designsystem.LocalPalette\n','import com.yfuse.core.designsystem.LocalPalette\nimport com.yfuse.core.designsystem.rememberSegmentIndicator\nimport com.yfuse.core.designsystem.selectionColor\n')
start=s.index('private fun SettingSegmentControl(');end=s.index('\n@Composable',start)
part=s[start:end].replace('    Row(\n','    val indicator = rememberSegmentIndicator(selectedIndex, palette.card2, accent.border)\n    Row(\n',1)
part=part.replace('.padding(2.dp),','.padding(2.dp).then(indicator.container),')
part=part.replace('.heightIn(min = 30.dp)','.heightIn(min = 30.dp).then(indicator.item(index))')
startglass=part.index('                    .liquidGlass(') if '                    .liquidGlass(' in part else part.index('                    .liquidGlass(')
endglass=part.index(').padding(horizontal',startglass)
part=part[:startglass]+part[endglass+1:]
part=part.replace('color = if (isSelected) accent.accent else palette.sub2,','color = selectionColor(if (isSelected) accent.accent else palette.sub2),')
s=s[:start]+part+s[end:];write(p,s)
