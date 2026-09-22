from edit import read, write, replace
import re

base = 'composeApp/src/commonMain/kotlin/com/yfuse/'
ds = base + 'core/designsystem/'

write(ds + 'DecorativePhase.kt', '''package com.yfuse.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState

/** A decorative clock exists only while its route, feature and accessibility policy allow it. */
@Composable
internal fun rememberDecorativePhase(
    enabled: Boolean = true,
    periodMillis: Int,
    rest: Float = 0f,
    repeatMode: RepeatMode = RepeatMode.Restart,
    label: String,
): State<Float> =
    if (enabled && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion) {
        rememberInfiniteTransition(label = label).animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), repeatMode),
            label = label + "Phase",
        )
    } else {
        rememberUpdatedState(rest)
    }
''')
p = base + 'feature/detail/DetailLoading.kt'
s = read(p)
start = s.index('    val reduceMotion =', s.index('private fun Modifier.heroBloom'))
end = s.index('    val strength =',start)
s = s[:start] + '''    val phase = rememberDecorativePhase(
        periodMillis = Motion.DETAIL_LOADING_BLOOM,
        rest = 0.5f,
        repeatMode = RepeatMode.Reverse,
        label = "detailBloom",
    )
''' + s[end:]
s=s.replace('    return drawBehind {\n        drawRect(fill)', '    return drawBehind {\n        val drift = phase.value\n        drawRect(fill)')
s=s.replace('private const val BLOOM_DRIFT_MS = 6_000\n','')
s=s.replace('import com.yfuse.core.designsystem.LocalAccessibilityOptions\n', 'import com.yfuse.core.designsystem.Motion\nimport com.yfuse.core.designsystem.rememberDecorativePhase\n')
for imp in ['LinearEasing','animateFloat','infiniteRepeatable','rememberInfiniteTransition','tween']:
    s=s.replace('import androidx.compose.animation.core.'+imp+'\n','')
s=s.replace('import androidx.compose.runtime.getValue\n','')
write(p,s)

p=base+'feature/profile/DownloadsScreen.kt'
s=read(p)
start=s.index('    val transition = rememberInfiniteTransition(label = "downloadFlow")')
end=s.index('    Box(Modifier.fillMaxWidth().height(4.dp))',start)
s=s[:start]+'''    val flow = rememberDecorativePhase(
        enabled = flowing,
        periodMillis = Motion.DOWNLOAD_FLOW,
        label = "downloadFlow",
    )
'''+s[end:]
s=s.replace('* flow\n','* flow.value\n').replace('DOWNLOAD_COMPLETE_MS','Motion.DOWNLOAD_COMPLETE')
s=s.replace('private const val Motion.DOWNLOAD_COMPLETE = 380\n','').replace('private const val DOWNLOAD_FLOW_MS = 1_400\n','')
s=s.replace('import com.yfuse.core.designsystem.pressable\n','import com.yfuse.core.designsystem.pressable\nimport com.yfuse.core.designsystem.rememberDecorativePhase\n')
for imp in ['LinearEasing','animateFloat','infiniteRepeatable','rememberInfiniteTransition']:
    s=s.replace('import androidx.compose.animation.core.'+imp+'\n','')
write(p,s)

p=base+'app/App.kt'
s=read(p)
start=s.index('    val initialIndex = selectedIndex.coerceAtLeast(0).toFloat()')
end=s.index('    val indicatorAlpha by',start)
old=s[start:end]
write(base+'app/DefaultTabMotion.kt','''package com.yfuse.app

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.yfuse.core.designsystem.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class DefaultTabMotion(val left: State<Float>, val right: State<Float>)

/** Created only by the standard path; switching styles starts at the current selection. */
@Composable
internal fun rememberDefaultTabMotion(selectedIndex: Int, reduceMotion: Boolean): DefaultTabMotion {
'''+old.replace('LaunchedEffect(selectedIndex, reduceMotion, enhanced)', 'LaunchedEffect(selectedIndex, reduceMotion)').replace('if (reduceMotion || enhanced)', 'if (reduceMotion)')+'''
    return remember { DefaultTabMotion(indicatorLeft.asState(), indicatorRight.asState()) }
}
''')
s=s[:start]+'''    val defaultMotion = if (enhanced) null else rememberDefaultTabMotion(selectedIndex, reduceMotion)
'''+s[end:]
s=s.replace('?: indicatorLeft.value','?: checkNotNull(defaultMotion).left.value').replace('?: indicatorRight.value','?: checkNotNull(defaultMotion).right.value')
s=s.replace('animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.76f, stiffness = 460f)', 'animationSpec = Motion.tabIcon(reduceMotion)')
# Keep indicator alpha reads in the same draw phase as its edges.
s=s.replace('val indicatorAlpha by animateFloatAsState(', 'val indicatorAlpha = animateFloatAsState(')
s=s.replace('if (indicatorAlpha <= 0f)', 'if (indicatorAlpha.value <= 0f)').replace('val alpha = indicatorAlpha.coerceIn', 'val alpha = indicatorAlpha.value.coerceIn')
write(p,s)

p=base+'app/LiquidTabMotion.kt'
s=read(p)
s=s.replace('    val leading = if (dragIndex != null) 700f else 300f\n    val trailing = if (dragIndex != null) 240f else 155f\n','')
s=s.replace('if (reduceMotion) snap() else spring(0.92f, if (rightward) trailing else leading)', 'Motion.liquidTabEdge(reduceMotion, dragging = dragIndex != null, leading = !rightward)')
s=s.replace('if (reduceMotion) snap() else spring(0.92f, if (rightward) leading else trailing)', 'Motion.liquidTabEdge(reduceMotion, dragging = dragIndex != null, leading = rightward)')
s=s.replace('tween(520, delayMillis = 90, easing = Motion.Curve)', 'tween(Motion.TAB_SWEEP, delayMillis = Motion.TAB_SWEEP_DELAY, easing = Motion.Curve)')
s=s.replace('import androidx.compose.animation.core.snap\n','').replace('import androidx.compose.animation.core.spring\n','')
write(p,s)

p=ds+'Tokens.kt'
s=read(p).replace('the single easing used by every transition.', 'the default easing; tuned dialog curves are kept in [Dialog].')
at=s.index('    // ------------------------------------------------------------ 弹簧',s.index('object Motion'))
s=s[:at]+'''    // Decorative periods and individually tuned arrivals retain their existing timing.
    const val DETAIL_LOADING_BLOOM = 6_000
    const val DOWNLOAD_FLOW = 1_400
    const val DOWNLOAD_COMPLETE = 380
    const val THEME_CROSSFADE = 380
    const val ORB_COMET = 1_200
    const val TAB_SWEEP = 520
    const val TAB_SWEEP_DELAY = 90

    /** Shared dialog timing slots; stored enum names and all existing durations stay intact. */
    object Dialog {
        const val ENTER_QUICK = 360
        const val ENTER_STANDARD = 380
        const val ENTER_EMPHASIZED = 400
        const val ENTER_EXTENDED = 420
        const val EXIT_QUICK = 240
        const val EXIT_COMPACT = 250
        const val EXIT_STANDARD = 260
        const val EXIT_EMPHASIZED = 280
        const val EXIT_EXTENDED = 300
        val EnterCurve = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.45f, 0.25f, 1f)
        val ExitCurve = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.75f, 0.65f)
    }

'''+s[at:]
s=s.replace('spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium)', 'spring(dampingRatio = 0.76f, stiffness = 460f)')
at=s.index('    /** 推进（详情')
s=s[:at]+'''    /** The dragged liquid edge is tighter than its tail; release retains the tuned weight. */
    fun liquidTabEdge(reduceMotion: Boolean, dragging: Boolean, leading: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) {
            snap()
        } else {
            val stiffness = when {
                dragging && leading -> 700f
                dragging -> 240f
                leading -> 300f
                else -> 155f
            }
            spring(dampingRatio = 0.92f, stiffness = stiffness)
        }

'''+s[at:]
write(p,s)
replace(ds+'ThemeCrossfade.kt','const val THEME_CROSSFADE_MS = 380', 'const val THEME_CROSSFADE_MS = Motion.THEME_CROSSFADE')
replace(ds+'OrbProgress.kt','internal const val ORB_COMET_MS = 1_200','internal const val ORB_COMET_MS = Motion.ORB_COMET')

p=ds+'DialogAnimation.kt'
s=read(p)
ent={360:'QUICK',380:'STANDARD',400:'EMPHASIZED',420:'EXTENDED'}
ext={240:'QUICK',250:'COMPACT',260:'STANDARD',280:'EMPHASIZED',300:'EXTENDED'}
s,n=re.subn(r', (360|380|400|420), (240|250|260|280|300)\),',lambda m: ', Motion.Dialog.ENTER_'+ent[int(m[1])]+', Motion.Dialog.EXIT_'+ext[int(m[2])]+'),',s)
assert n==43,n
at=s.index('internal data class DialogMotionFrame(')
s=s[:at]+'''/** Normalized progress shared by the staged dialog masks and transforms. */
internal fun dialogStage(progress: Float, start: Float, end: Float): Float =
    ((progress - start) / (end - start)).coerceIn(0f, 1f)

'''+s[at:]
write(p,s)
for file,name in [('CuriousDialogMotion.kt','curiousStage'),('DelightDialogMotion.kt','delightStage')]:
    s=read(ds+file)
    s,n=re.subn(r'private fun '+name+r'\([\s\S]*?\): Float = [^\n]+\n\n','',s,count=1)
    assert n==1
    write(ds+file,s.replace(name+'(', 'dialogStage('))
p=ds+'Dialogs.kt'
s=read(p).replace('import androidx.compose.animation.core.CubicBezierEasing\n','')
s=s.replace('private val OverlayEnterCurve = CubicBezierEasing(0.2f, 0.45f, 0.25f, 1f)\n','').replace('private val OverlayExitCurve = CubicBezierEasing(0.4f, 0f, 0.75f, 0.65f)\n','')
s=s.replace('OverlayEnterCurve','Motion.Dialog.EnterCurve').replace('OverlayExitCurve','Motion.Dialog.ExitCurve')
write(p,s)
