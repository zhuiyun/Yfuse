from edit import read, write, replace
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
write(C+'core/designsystem/SearchDockMotion.kt', '''package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** Only numeric bounds are retained, never a view, context, image, or layout coordinates. */
internal object SearchDockOrigin {
    var bounds: Rect? = null
    private var pending: Rect? = null

    fun begin() { pending = bounds }
    fun consume(): Rect? = pending.also { pending = null }
}

internal fun Modifier.searchDockSource(): Modifier =
    onGloballyPositioned { SearchDockOrigin.bounds = it.boundsInWindow() }

/** A navigation click grants one morph. Query edits, pages and returning to the route do not. */
@Composable
internal fun Modifier.searchFieldArrival(): Modifier {
    val origin = remember { SearchDockOrigin.consume() }
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    var target by remember { mutableStateOf<Rect?>(null) }
    val progress = remember { Animatable(if (origin == null || !moving) 1f else 0f) }
    LaunchedEffect(target, moving) {
        if (!moving) progress.snapTo(1f)
        else if (target != null) progress.animateTo(1f, Motion.tween(Motion.MODAL))
    }
    return onGloballyPositioned {
        // Capture the untransformed bounds once. Later graphics-layer positions are not targets.
        if (target == null) target = it.boundsInWindow()
    }.graphicsLayer {
        val destination = target
        if (moving && origin != null && destination != null && destination.width > 0f && destination.height > 0f) {
            val p = progress.value
            val remaining = 1f - p
            translationX = (origin.center.x - destination.center.x) * remaining
            translationY = (origin.center.y - destination.center.y) * remaining
            scaleX = 1f + (origin.width / destination.width - 1f) * remaining
            scaleY = 1f + (origin.height / destination.height - 1f) * remaining
            alpha = 0.35f + 0.65f * p
        }
    }
}
'''.replace('Motion.tween(Motion.MODAL)', 'androidx.compose.animation.core.tween(Motion.MODAL, easing = Motion.Curve)'))
p=C+'app/App.kt'; s=read(p)
s=s.replace('import com.yfuse.core.designsystem.AppShapes', 'import com.yfuse.core.designsystem.SearchDockOrigin\nimport com.yfuse.core.designsystem.searchDockSource\nimport com.yfuse.core.designsystem.AppShapes')
start=s.index('private fun SearchButton('); end=s.index('\n@Composable',start)
part=s[start:end].replace('.size(Dimens.tabBarHeight)', '.size(Dimens.tabBarHeight)\n            .searchDockSource()',1)
part=part.replace('onClick = onClick,', 'onClick = { SearchDockOrigin.begin(); onClick() },',1)
write(p,s[:start]+part+s[end:])
p=C+'feature/search/SearchScreen.kt'
replace(p,'import com.yfuse.core.designsystem.selectionColor','import com.yfuse.core.designsystem.searchFieldArrival\nimport com.yfuse.core.designsystem.selectionColor')
replace(p,'.shadow(Shadows.searchBarFocused, shape)', '.searchFieldArrival()\n            .shadow(Shadows.searchBarFocused, shape)')
p=C+'feature/search/SearchResultsHandoff.kt';s=read(p)
s=s.replace('import androidx.compose.runtime.remember','import androidx.compose.runtime.remember\nimport androidx.compose.runtime.snapshotFlow')
s=s.replace('import kotlinx.coroutines.delay','import kotlinx.coroutines.delay\nimport kotlinx.coroutines.flow.first')
s=s.replace('private const val SEARCH_REVEAL_MS = 680','private const val SEARCH_REVEAL_MS = Motion.SEARCH_REVEAL')
s=s.replace('private const val SEARCH_WAIT_HALF_CYCLE_MS = 850','private const val SEARCH_WAIT_HALF_CYCLE_MS = Motion.WAIT_HALF_CYCLE')
s=s.replace('index.coerceIn(0, 5) * 0.07f','index.coerceIn(0, 5) * Motion.SEARCH_ROW_STAGGER.toFloat() / SEARCH_REVEAL_MS')
s=s.replace('''        pulse.snapTo(0f)
        if (waiting) {''','''        if (!waiting) {
            if (moving) pulse.animateTo(0f, tween(Motion.STANDARD, easing = Motion.Curve)) else pulse.snapTo(0f)
        } else {''')
s=s.replace('''                if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
                    pulse.snapTo(0f)
                    return@LaunchedEffect
                }''','''                val durationScale = coroutineContext[MotionDurationScale]
                if (durationScale?.scaleFactor == 0f) {
                    pulse.snapTo(0f)
                    snapshotFlow { durationScale.scaleFactor }.first { it > 0f }
                }''')
s=s.replace('if (waiting) {\n                    drawRoundRect(', 'if (waiting || pulse.value > 0f) {\n                    drawRoundRect(',1)
s=s.replace('if (!waiting) {\n            Modifier\n        } else {\n            Modifier.graphicsLayer {\n                scaleX = 1.05f + 0.16f * pulse.value', 'if (!highlights) {\n            Modifier\n        } else {\n            Modifier.graphicsLayer {\n                scaleX = 1f + 0.16f * pulse.value')
s=s.replace('rotationZ = -8f + 16f * pulse.value','rotationZ = 8f * pulse.value')
write(p,s)
p=C+'core/designsystem/Tokens.kt'
replace(p,'    const val NEXT_UP_INTERPOLATION = 500','''    const val NEXT_UP_INTERPOLATION = 500
    const val SEARCH_REVEAL = 680
    const val SEARCH_ROW_STAGGER = 55
    const val WAIT_HALF_CYCLE = 850
    const val ARRIVAL_REVEAL = 480
    const val ATTENTION_SWEEP = 520
    const val BURST = 420
    const val BURST_RELEASE = 200
    const val SKELETON_PULSE = 1_600
    const val SKELETON_SWEEP = 2_800
    const val SKELETON_PHASE_STEP = 110
    const val PLAYER_SEEK_FEEDBACK = 420''')
# Small watch status scopes; changing latency does not replay a readiness handoff.
p=C+'feature/player/WatchTogetherDialogs.kt';s=read(p)
s=s.replace('import androidx.compose.foundation.horizontalScroll','import androidx.compose.foundation.lazy.LazyRow\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.horizontalScroll')
s=s.replace('import com.yfuse.core.designsystem.AppShapes','import com.yfuse.core.designsystem.contentHandoff\nimport com.yfuse.core.designsystem.motionAwareItem\nimport com.yfuse.core.designsystem.AppShapes')
s=s.replace('''                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .horizontalScroll(rememberScrollState()),''','''                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),''',1)
s=s.replace('participants.forEach { participant ->','items(participants, key = { it.clientId }) { participant ->',1)
s=s.replace('.width(108.dp),','.width(108.dp).motionAwareItem(),',1)
s=s.replace('''                                participant.playbackStatusLabel,
                                style''','''                                participant.playbackStatusLabel,
                                modifier = Modifier.contentHandoff(participant.playbackStatusLabel),
                                style''',1)
s=s.replace('.glass(AppShapes.card, palette.card2, palette.border)', '.contentHandoff(connected to connecting)\n                    .glass(AppShapes.card, palette.card2, palette.border)',1)
write(p,s)
p=C+'feature/watch/CopyableRoomCode.kt'
replace(p,'import com.yfuse.core.designsystem.AppTypography','import com.yfuse.core.designsystem.contentHandoff\nimport com.yfuse.core.designsystem.AppTypography')
replace(p,'text = if (copied) "已复制房间码" else "点击或长按复制房间码",','text = if (copied) "已复制房间码" else "点击或长按复制房间码",\n            modifier = Modifier.contentHandoff(copied),')
