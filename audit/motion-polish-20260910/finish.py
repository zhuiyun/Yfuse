from edit import read,write,replace
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
def imports(p,*names):
    s=read(p)
    for n in names:
        line='import '+n+'\n'
        if line not in s:s=s.replace('\n\nimport ','\n\n'+line+'import ',1)
    write(p,s)

write(C+'core/designsystem/RefreshIndicator.kt','''package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Same glass/orb language as inline loading; the pull distance is consumed by the layer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier = Modifier) {
    val shown by remember(state, refreshing) { derivedStateOf { refreshing || state.distanceFraction > 0f } }
    if (!shown) return
    val palette = LocalPalette.current
    val reduced = LocalAccessibilityOptions.current.reduceMotion
    Box(modifier.statusBarsPadding().graphicsLayer {
        val fraction = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
        translationY = (-48f + 64f * fraction).dp.toPx()
        alpha = fraction
        scaleX = if (reduced) 1f else 0.8f + 0.2f * fraction
        scaleY = scaleX
    }.size(40.dp).glass(GlassShapes.chip, palette.card, palette.border).padding(10.dp), contentAlignment = Alignment.Center) {
        InlineLoadingContent(loading = refreshing, slotSize = 20.dp, color = LocalAccentColors.current.accent) {
            Icon(AppIcons.Refresh, contentDescription = "下拉刷新", tint = LocalAccentColors.current.accent,
                modifier = Modifier.size(20.dp).graphicsLayer {
                    rotationZ = if (reduced) 0f else state.distanceFraction.coerceIn(0f, 1f) * 180f
                })
        }
    }
}
''')
for name,expr in [('home/HomeScreen.kt','state.refreshing'),('library/LibraryHomeScreen.kt','state.refreshing'),('servers/ServersTabScreen.kt','refreshing')]:
    p=C+'feature/'+name;imports(p,'com.yfuse.core.designsystem.RefreshIndicator')
    s=read(p).replace('state = pullState,','state = pullState,\n            indicator = { RefreshIndicator(pullState, '+expr+', Modifier.align(Alignment.TopCenter)) },')
    write(p,s)

# Resize the real server cards inside one lookahead tree, excluding scroll motion from interpolation.
p=C+'feature/servers/ServersTabScreen.kt'
imports(p,'androidx.compose.animation.animateBounds','androidx.compose.animation.ExperimentalSharedTransitionApi','androidx.compose.ui.layout.LookaheadScope','androidx.compose.foundation.lazy.grid.items')
s=read(p)
if not s.startswith('@file:OptIn'):s='@file:OptIn(ExperimentalSharedTransitionApi::class)\n\n'+s
else:s=s.replace('@file:OptIn(', '@file:OptIn(ExperimentalSharedTransitionApi::class, ',1)
s=s.replace('                LazyVerticalGrid(', '                LookaheadScope {\n                val cardLookahead = this\n                LazyVerticalGrid(',1)
s=s.replace('motionItems(otherVisibleServers, key = { it.id }, contentType = { "server-card" })', 'items(otherVisibleServers, key = { it.id }, contentType = { "server-card" })')
start=s.index('items(otherVisibleServers, key');end=s.index('        // A new request',start)
chunk=s[start:end].replace('modifier = Modifier,','modifier = if (LocalAccessibilityOptions.current.reduceMotion || !routeVisible) Modifier else Modifier.animateBounds(cardLookahead, boundsTransform = { _, _ -> Motion.settle() }),')
chunk=chunk.replace('                }\n            }\n        }','                }\n                }\n            }\n        }')
s=s[:start]+chunk+s[end:];write(p,s)

# Skeletons remain until the invitation/creation response is ready; error/empty/readiness share the same host.
p=C+'feature/watch/WatchInviteSheet.kt'
imports(p,'androidx.compose.foundation.layout.heightIn','com.yfuse.core.designsystem.SkeletonHandoff','com.yfuse.core.designsystem.PageLoadingSkeleton','com.yfuse.core.designsystem.contentHandoff','com.yfuse.core.designsystem.motionContentSize')
s=read(p)
s=s.replace('''        } else {
            when (resolution) {''','''        } else {
            SkeletonHandoff(
                loading = resolution == InviteResolution.Resolving,
                modifier = Modifier.fillMaxWidth().motionContentSize().heightIn(min = 180.dp),
                skeleton = { PageLoadingSkeleton() },
            ) {
            Column(Modifier.fillMaxWidth().contentHandoff(resolution::class)) {
            when (resolution) {''',1)
# New outer scopes close after the existing resolution switch.
idx=s.index('\n/**\n * The host')
part=s[:idx];pos=part.rfind('            }\n        }\n    }\n}')
assert pos>=0
part=part[:pos]+part[pos:].replace('            }\n        }\n    }\n}', '            }\n            }\n            }\n        }\n    }\n}',1)
s=part+s[idx:]
s=s.replace('''        when {
            error != null -> {''','''        SkeletonHandoff(
            loading = roomCode == null && error == null,
            modifier = Modifier.fillMaxWidth().motionContentSize().heightIn(min = 190.dp),
            skeleton = { PageLoadingSkeleton() },
        ) {
        Column(Modifier.fillMaxWidth().contentHandoff(error != null)) {
        when {
            error != null -> {''',1)
pos=s.rfind('        }\n    }\n}')
s=s[:pos]+s[pos:].replace('        }\n    }\n}', '        }\n        }\n        }\n    }\n}',1)
write(p,s)

# First root back asks; during its confirmation window no app callback blocks Android's second gesture.
p=A+'MainActivity.kt'
imports(p,'kotlinx.coroutines.delay')
s=read(p).replace('    private var lastExitBackPressMs = 0L','    private var exitRearmJob: Job? = null\n    private var exitBackCallback: OnBackPressedCallback? = null')
start=s.index('                    val now = SystemClock.elapsedRealtime()');end=s.index('                    exitConfirmationToast?.cancel()',s.index('                    lastExitBackPressMs = now',start))
s=s[:start]+'''                    isEnabled = false
                    exitRearmJob?.cancel()
                    exitRearmJob = lifecycleScope.launch {
                        delay(EXIT_CONFIRMATION_WINDOW_MS)
                        isEnabled = true
                    }
'''+s[end:]
s=s.replace('''            },
        )
        preferHighRefreshRateForUi()''','''            }.also { exitBackCallback = it },
        )
        preferHighRefreshRateForUi()''',1)
s=s.replace('        lastExitBackPressMs = 0L','        exitRearmJob?.cancel()\n        exitBackCallback?.isEnabled = true')
write(p,s)

# Android keeps the video Surface throughout PiP; request seamless platform resizing where supported.
p=A+'feature/player/PlayerActivity.kt'
s=read(p).replace('setAutoEnterEnabled(activeState.playing)', 'setAutoEnterEnabled(activeState.playing)\n                        setSeamlessResizeEnabled(true)')
s=s.replace('.apply { videoBounds?.let(::setSourceRectHint) }','.apply {\n                    videoBounds?.let(::setSourceRectHint)\n                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setSeamlessResizeEnabled(true)\n                }')
write(p,s)
