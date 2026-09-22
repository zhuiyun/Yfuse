from edit import read, write, replace

C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
def imports(p, *names):
    s=read(p)
    for name in names:
        line='import '+name+'\n'
        if line not in s: s=s.replace('\n\nimport ', '\n\n'+line+'import ',1)
    write(p,s)

write(C+'core/designsystem/MotionPolish.kt', '''package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Small immutable labels/glyphs only: never duplicate a live player or a screen's effects. */
@Composable
internal fun <T> MotionSwap(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val duration = if (LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current) 0 else Motion.QUICK
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)))
                .using(SizeTransform(clip = false) { _, _ -> tween(duration, easing = Motion.Curve) })
        },
        contentAlignment = Alignment.Center,
        label = "small-state-handoff",
    ) { content(it) }
}

/** Keep the scroll slot stable; only the rendered hero recedes as it leaves the viewport. */
@Composable
internal fun Modifier.heroScrollCollapse(state: LazyListState, height: Dp): Modifier {
    val moving = !LocalAccessibilityOptions.current.reduceMotion && LocalRouteVisible.current
    return graphicsLayer {
        val amount = if (!moving || state.firstVisibleItemIndex != 0) 0f else
            (state.firstVisibleItemScrollOffset / height.toPx().coerceAtLeast(1f)).coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(0.5f, 0f)
        scaleX = 1f - 0.04f * amount
        scaleY = 1f - 0.16f * amount
        translationY = height.toPx() * 0.12f * amount
        alpha = 1f - amount * amount
        clip = amount > 0f
    }
}

/** One content tree survives a window-tier/orientation change; no player Surface snapshots. */
@Composable
internal fun Modifier.windowSizeHandoff(): Modifier {
    val density = LocalDensity.current.density
    var windowClass by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    return onSizeChanged { size ->
        if (size.width > 0 && size.height > 0) {
            val width = size.width / density
            windowClass = (if (width < 600f) 0 else if (width < 840f) 1 else 2) to (size.width > size.height)
        }
    }.contentHandoff(windowClass ?: "initial-window")
}
''')

# Hero collapse reads scroll only in the layer, not composition.
p=C+'feature/home/HomeScreen.kt'
imports(p,'com.yfuse.core.designsystem.heroScrollCollapse')
s=read(p).replace('motionItem(key = "home-hero", arrival = false) {\n                        HomeHeroCarousel(', 'motionItem(key = "home-hero", arrival = false) {\n                        Box(Modifier.heroScrollCollapse(listState, heroHeight)) {\n                        HomeHeroCarousel(')
s=s.replace('onPageColor = onHeroPageColor,\n                        )','onPageColor = onHeroPageColor,\n                        )\n                        }')
write(p,s)

# Library rows get real lazy identities, so skeleton removal and row placement share the lazy animator.
p=C+'feature/library/LibraryHomeScreen.kt'
imports(p,'com.yfuse.core.designsystem.arrivalSweep','com.yfuse.core.designsystem.rememberRefreshReveal','com.yfuse.core.designsystem.heroScrollCollapse','com.yfuse.core.designsystem.MotionSwap','com.yfuse.core.designsystem.selectionColor')
s=read(p).replace('RefreshThresholdHaptics(pullState, refreshing = state.loading)','RefreshThresholdHaptics(pullState, refreshing = state.refreshing)\n    val refreshArrival = rememberRefreshReveal(state.refreshing)')
s=s.replace('Modifier.fillMaxSize().skeletonSweep(),','Modifier.fillMaxSize().skeletonSweep().arrivalSweep(refreshArrival),')
s=s.replace('.height(\n                                                    heroHeight,\n                                                ).carouselTouchPause', '.height(\n                                                    heroHeight,\n                                                ).heroScrollCollapse(listState, heroHeight).carouselTouchPause')
start=s.index('                                motionItem {\n                                    val liftPx')
end=s.index('\n                            }\n                        }\n                    }',start)
s=s[:start]+'''                                if (state.contentSource == LibraryContentSource.Cached && !state.content.isEmpty) {
                                    motionItem(key = "library-freshness") {
                                        LibraryFreshnessBanner(
                                            updatedAtEpochMs = state.updatedAtEpochMs,
                                            nowEpochMs = freshnessNowEpochMs,
                                            error = state.error,
                                            loading = state.loading,
                                            onRetry = { store.accept(LibraryIntent.Retry) },
                                        )
                                    }
                                }
                                if (state.loading && state.content.isEmpty) {
                                    motionItem(key = "library-loading") { SkeletonRow() }
                                }
                                if (state.content.rows.isNotEmpty() || state.currentServer != null) {
                                    motionItem(key = "library-categories") {
                                        CategoryCards(
                                            baseUrl = baseUrl, accessToken = accessToken,
                                            rows = state.content.rows, playlists = state.content.playlists,
                                            onOpen = { component.onSeeAll(it.libraryId, it.title) },
                                            onOpenPlaylists = {
                                                state.currentServer?.id?.let { serverId ->
                                                    component.onSeeAll(
                                                        LibraryContainerDirectoryRoute(serverId, MediaContainerKind.Playlist).encode(),
                                                        "播放列表",
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                                if (state.content.resume.isNotEmpty()) {
                                    motionItem(key = "library-resume") {
                                        PlaybackHistory(
                                            baseUrl = baseUrl, accessToken = accessToken,
                                            serverId = state.currentServer?.id, items = state.content.resume,
                                            onItemClick = { component.onOpenItem(it.id) },
                                        )
                                    }
                                }
                                state.content.rows.libraryShelfRows().forEach { row ->
                                    motionItem(key = "library-shelf:${row.libraryId}:${row.title}") {
                                        CategorySection(
                                            baseUrl = baseUrl, accessToken = accessToken,
                                            serverId = state.currentServer?.id, row = row,
                                            onSeeAll = { component.onSeeAll(row.libraryId, row.title) },
                                            onItemClick = { component.onOpenItem(it.id) },
                                        )
                                    }
                                }
                                state.content.counts?.let { counts ->
                                    motionItem(key = "library-counts") {
                                        LibraryCountFooter(counts.movieCount, counts.seriesCount)
                                    }
                                }''' +s[end:]
# Preserve existing visual gap (old column's padding and negative placement cancelled exactly).
s=s.replace('state = listState,\n                                contentPadding', 'state = listState,\n                                verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),\n                                contentPadding')
s=s.replace('''fill =
                                if (isCurrent) {
                                    themeAccent.container
                                } else {
                                    palette.card2
                                },''','''fill = selectionColor(if (isCurrent) themeAccent.container else palette.card2),''')
s=s.replace('''border =
                                if (isCurrent) {
                                    themeAccent.border.copy(alpha = 0.30f)
                                } else {
                                    palette.border
                                },''','''border = selectionColor(if (isCurrent) themeAccent.border.copy(alpha = 0.30f) else palette.border),''')
write(p,s)

p=C+'feature/library/LibraryGridScreen.kt'
imports(p,'com.yfuse.core.designsystem.MotionSwap')
s=read(p); start=s.index('                Text(\n                    "${state.totalCount'); end=s.index('\n                if (state.sortable)',start)
s=s[:start]+'''                MotionSwap(state.totalCount.coerceAtLeast(state.loadedCount) to state.directoryKind) { (count, directory) ->
                    Text("$count ${if (directory != null) "个" else "部"}", style = AppTypography.caption.medium, color = palette.sub2)
                }'''+s[end:];write(p,s)

# Buffer interpolation only invalidates paint. A backwards/reset target snaps immediately.
p=C+'feature/player/PlayerChromeRefined.kt'
imports(p,'androidx.compose.animation.core.Animatable','androidx.compose.runtime.LaunchedEffect','com.yfuse.core.designsystem.LocalRouteVisible')
s=read(p).replace('val buffered = rememberUpdatedState(bufferedFraction)', '''val buffered = remember(durationMs) { Animatable(bufferedFraction.coerceIn(0f, 1f)) }
        val moving = !reduceMotion && LocalRouteVisible.current
        LaunchedEffect(bufferedFraction, durationMs, moving) {
            val target = bufferedFraction.coerceIn(0f, 1f)
            if (!moving || target < buffered.value) buffered.snapTo(target)
            else buffered.animateTo(target, tween(Motion.STANDARD, easing = Motion.Curve))
        }''');write(p,s)

# Server layout now relies on stable item placement instead of fading the whole grid.
p=C+'feature/servers/ServersTabScreen.kt'
imports(p,'com.yfuse.core.designsystem.MotionSwap')
s=read(p).replace('layout to visibleServers.isEmpty(),','visibleServers.isEmpty(),')
start=s.index('                Icon(\n                    when (refreshFeedback?.result)')
end=s.index('\n            }\n        }\n    }',start)
old=s[start:end]; old=old.replace('when (refreshFeedback?.result)', 'when (result)')
s=s[:start]+'                MotionSwap(refreshFeedback?.result) { result ->\n'+old+'\n                }'+s[end:];write(p,s)

# Size-tier changes use a single retained navigation tree.
p=C+'core/designsystem/OfficialNavDisplay.kt'
replace(p,'SharedTransitionLayout(modifier) {','SharedTransitionLayout(modifier.windowSizeHandoff()) {')

# Input insets: common source already targets Android/TV, same Compose API as its existing imePadding.
for rel in ['core/designsystem/Dialogs.kt','feature/servers/ServersScreen.kt','feature/player/PlayerPanel.kt']:
    p=C+rel
    imports(p,'androidx.compose.foundation.layout.imeNestedScroll','androidx.compose.foundation.layout.ExperimentalLayoutApi')
    s=read(p)
    if not s.startswith('@file:OptIn'): s='@file:OptIn(ExperimentalLayoutApi::class)\n\n'+s
    s=s.replace('.imePadding()', '.imePadding().imeNestedScroll()');write(p,s)
p=C+'feature/search/SearchScreen.kt'
imports(p,'androidx.compose.foundation.layout.imeNestedScroll','androidx.compose.foundation.layout.imePadding','androidx.compose.foundation.layout.ExperimentalLayoutApi')
s=read(p)
if not s.startswith('@file:OptIn'): s='@file:OptIn(ExperimentalLayoutApi::class)\n\n'+s
s=s.replace('Box(Modifier.fillMaxSize()', 'Box(Modifier.fillMaxSize().imePadding().imeNestedScroll()',1);write(p,s)
