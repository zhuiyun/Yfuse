from edit import read, write, replace

base = 'composeApp/src/commonMain/kotlin/com/yfuse/'
write(base + 'core/designsystem/InlineLoadingContent.kt', '''package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Reserve the larger icon slot; optional trailing text changes width with the handoff. */
@Composable
internal fun InlineLoadingContent(
    loading: Boolean,
    slotSize: Dp,
    color: Color,
    orbSize: Dp = slotSize,
    content: @Composable () -> Unit,
) {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            (fadeIn(if (moving) tween(Motion.QUICK, easing = Motion.Curve) else snap()) togetherWith
                fadeOut(if (moving) tween(Motion.QUICK, easing = Motion.Curve) else snap()))
                .using(SizeTransform(clip = false) { _, _ ->
                    if (moving) tween(Motion.QUICK, easing = Motion.Curve) else snap()
                })
        },
        contentAlignment = Alignment.Center,
        label = "inline-loading",
    ) { busy ->
        Box(Modifier.defaultMinSize(minWidth = slotSize, minHeight = slotSize), contentAlignment = Alignment.Center) {
            if (busy) {
                OrbProgress(size = orbSize, color = color)
            } else {
                content()
            }
        }
    }
}
''')

# Keep the existing icon subtree and semantics, replacing only the loading branch.
def switch(path, condition, orb, args):
    path = base + path
    text = read(path)
    needle = f'if ({condition}) {{\n'
    start = text.index(needle, text.index(orb) - 100)
    else_start = text.index('} else {', start)
    assert orb in text[start:else_start]
    content_start = else_start + len('} else {')
    depth = 1
    end = content_start
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    text = text[:start] + f'InlineLoadingContent({args}) {{' + text[content_start:end] + text[end:]
    if 'import com.yfuse.core.designsystem.InlineLoadingContent\n' not in text:
        text = text.replace('import com.yfuse.core.designsystem.OrbProgress\n', 'import com.yfuse.core.designsystem.InlineLoadingContent\nimport com.yfuse.core.designsystem.OrbProgress\n')
    if text.count('OrbProgress') == 1:
        text = text.replace('import com.yfuse.core.designsystem.OrbProgress\n', '')
    write(path, text)

switch('feature/detail/DetailActions.kt', 'resolving', 'OrbProgress(size = 15.dp, color = actionInk)',
       'loading = resolving, slotSize = 15.dp, color = actionInk')
switch('feature/detail/DetailActions.kt', 'loading', 'OrbProgress(size = 15.dp, color = if (active)',
       'loading = loading, slotSize = 16.dp, orbSize = 15.dp, color = if (active) stateColors.foreground else palette.body')
switch('feature/home/TmdbInfoScreen.kt', 'resolving', 'OrbProgress(size = 15.dp, color = Color.White)',
       'loading = resolving, slotSize = 15.dp, color = Color.White')
switch('feature/calendar/CalendarScreen.kt', 'state.loading', 'OrbProgress(size = 15.dp, color = accent.accent)',
       'loading = state.loading, slotSize = 15.dp, color = accent.accent')
switch('feature/profile/AccountSessionsScreen.kt', 'loading', 'OrbProgress(size = 18.dp, color = palette.sub2)',
       'loading = loading, slotSize = 20.dp, orbSize = 18.dp, color = palette.sub2')

path = base + 'feature/profile/AccountSettingsScreen.kt'
text = read(path)
start = text.index('        if (loading) {\n            OrbProgress(size = 16.dp')
end = text.index('\n    }\n}', start)
old = text[start:end]
idle = old[old.index('            trailingLabel?.let'):old.rindex('\n        }')]
new = '''        InlineLoadingContent(
            loading = loading,
            slotSize = 17.dp,
            orbSize = 16.dp,
            color = if (destructive) palette.error else accent.accent,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
''' + '\n'.join('    ' + line for line in idle.splitlines()) + '''
            }
        }'''
text = text[:start] + new + text[end:]
text = text.replace('import com.yfuse.core.designsystem.OrbProgress\n', 'import com.yfuse.core.designsystem.InlineLoadingContent\nimport com.yfuse.core.designsystem.OrbProgress\n')
# Retain the outgoing text while AnimatedContent fades that branch out.
text = text.replace('trailingLabel = if (inviteBusy) null else "生成",', 'trailingLabel = "生成",')
write(path, text)

path = base + 'core/designsystem/Poster.kt'
text = read(path)
text = text.replace('import androidx.compose.foundation.background', '''import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background''', 1)
text = text.replace('import androidx.compose.ui.graphics.Shape\n', 'import androidx.compose.ui.graphics.Shape\nimport androidx.compose.ui.graphics.TransformOrigin\n')
text = text.replace('import androidx.compose.ui.layout.ContentScale\n', 'import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.platform.LocalLayoutDirection\n')
text = text.replace('import androidx.compose.ui.unit.Dp\n', 'import androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.LayoutDirection\n')
start = text.index('        progress?.takeIf { it > 0f }?.let { rawProgress ->')
end = text.index('\n        }', start) + len('\n        }')
text = text[:start] + '''        progress?.takeIf { it > 0f }?.let { rawProgress ->
            // A recycled poster starts at its own value, never the previous artwork's progress.
            key(candidates) {
                val watched = rawProgress.coerceIn(0f, 1f)
                val moving = !reduceMotion && LocalRouteVisible.current
                val animatedWatched = animateFloatAsState(
                    targetValue = watched,
                    animationSpec = if (moving) tween(Motion.STANDARD, easing = Motion.Curve) else snap(),
                    label = "poster-watched",
                )
                val origin = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else 0f
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth().height(4.dp)
                        .background(Color.Black.copy(alpha = 0.42f)),
                )
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth().height(4.dp)
                        .graphicsLayer {
                            // Read in the layer: no per-frame composition or layout, including in grids.
                            scaleX = animatedWatched.value
                            transformOrigin = TransformOrigin(origin, 0.5f)
                        }
                        .background(PrimaryGradient),
                )
            }
        }''' + text[end:]
write(path, text)

# This change affects shared phone UI only; retain the existing regression suite and design gate.
path = 'audit/inline-loading-motion-20260910/test.ps1'
text = read(path).replace("':composeApp:testReleaseUnitTest', ':tvApp:testDebugUnitTest',\n    ':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test', ':macrobenchmark:compileBenchmarkKotlin',", "':composeApp:testReleaseUnitTest', ':composeApp:verifyDesignSystemUsage',")
# Do not include the audit script itself in the source manifest.
from edit import ROOT
(ROOT / path).write_text(text, encoding='utf-8')
