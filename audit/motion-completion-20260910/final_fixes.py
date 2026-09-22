from edit import read,write,replace
C='composeApp/src/commonMain/kotlin/com/yfuse/'
T='composeApp/src/commonTest/kotlin/com/yfuse/'
p=C+'feature/player/PlayerControls.kt'
replace(p,'transport.copy(positionMs = target.coerceIn(0L, transport.durationMs.coerceAtLeast(0L)))','''transport.copy(
                                    positionMs = if (transport.durationMs > 0L) target.coerceIn(0L, transport.durationMs)
                                        else target.coerceAtLeast(0L),
                                )''')
replace(C+'feature/player/PlayerNextUpOverlay.kt','state.currentIndex to episodes.getOrNull(state.currentIndex),','state.currentIndex to episodes.getOrNull(state.currentIndex)?.watchKey,')
# Calendar loading placeholders match its day-list silhouette rather than a generic poster rail.
p=C+'feature/calendar/CalendarScreen.kt';s=read(p)
s=s.replace('import com.yfuse.core.designsystem.SkeletonRail','import com.yfuse.core.designsystem.SkeletonBlock')
s=s.replace('''                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            SkeletonRail(Modifier.fillMaxWidth())
                        }''','''                        CalendarLoadingContent()''',1)
assert 'SkeletonRail(' not in s
start=s.index('@Composable\nprivate fun CalendarWeekHeader(')
s=s[:start]+'''/** Keep the calendar's header and day-row geometry while the first request is pending. */
@Composable
private fun CalendarLoadingContent() {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = Dimens.pageHorizontal)
            .clip(GlassShapes.card).background(palette.card2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkeletonBlock(Modifier.width(130.dp).height(20.dp))
        repeat(3) { index ->
            SkeletonBlock(Modifier.width(100.dp).height(16.dp), phaseMs = index * 110)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(Modifier.width(62.dp).height(88.dp), phaseMs = index * 110)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(16.dp), phaseMs = index * 110)
                    SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(14.dp), phaseMs = index * 110)
                }
            }
        }
    }
}

'''+s[start:]
s=s.replace('import com.yfuse.core.designsystem.SkeletonBlock','import com.yfuse.core.designsystem.SkeletonBlock\nimport com.yfuse.core.designsystem.Motion') if 'import com.yfuse.core.designsystem.Motion\n' not in s else s
s=s.replace('index * 110','index * Motion.SKELETON_PHASE_STEP')
write(p,s)
p=C+'feature/player/PlayerResumeNotice.kt';s=read(p)
s=s.replace('import androidx.compose.ui.graphics.Color','import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.Shadow')
s=s.replace('    ChromeVisibility(notice.visible,','''    val caption = AppTypography.caption.medium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 4f))
    ChromeVisibility(notice.visible,''',1)
s=s.replace('style = AppTypography.caption.medium','style = caption')
write(p,s)
# A zero system animation scale suspends the custom loop, and changing it back wakes the same request.
s=read(T+'core/designsystem/DecorativePhaseTest.kt')
fixture=s[s.index('    private class NoNodes'):s.rfind('}')]
write(T+'core/designsystem/WaitingPulsePolicyTest.kt','''package com.yfuse.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WaitingPulsePolicyTest {
    @Test
    fun zero_duration_scale_suspends_custom_loop_and_positive_scale_resumes_without_a_new_request() = runTest {
        val clock = BroadcastFrameClock()
        val scale = mutableStateOf(0f)
        val policy = object : MotionDurationScale { override val scaleFactor: Float get() = scale.value }
        val recomposer = Recomposer(coroutineContext + clock + policy)
        val runner = launch(clock + policy) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val reduced = mutableStateOf(false)
        var nanos = 0L
        fun frames() {
            repeat(4) {
                Snapshot.sendApplyNotifications()
                advanceTimeBy(20L)
                runCurrent()
                nanos += 20_000_000L
                clock.sendFrame(nanos)
                runCurrent()
            }
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalAccessibilityOptions provides AccessibilityOptions(reduceMotion = reduced.value)) {
                    Modifier.waitingPulse(true, CircleShape, Color.White)
                }
            }
            advanceTimeBy(200L)
            frames()
            assertFalse(clock.hasAwaiters)
            scale.value = 1f
            frames()
            assertTrue(clock.hasAwaiters)
            scale.value = 0f
            frames()
            frames()
            assertFalse(clock.hasAwaiters)
            scale.value = 1f
            frames()
            assertTrue(clock.hasAwaiters)
            reduced.value = true
            frames()
            assertFalse(clock.hasAwaiters)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

'''+fixture+'}\n')
