from edit import read,write,replace
C='composeApp/src/commonMain/kotlin/com/yfuse/'
T='composeApp/src/commonTest/kotlin/com/yfuse/'
p=C+'feature/player/PlayerControls.kt';s=read(p)
s=s.replace('private const val RESUME_NOTICE_MS = 6_000L\n','')
# The lifetime belongs outside the locked/overlay content branch, so toggling controls cannot recreate the offer.
s=s.replace('    val state by rememberPlayerControlSnapshot(playback)','''    val state by rememberPlayerControlSnapshot(playback)''',1)
start=s.index('        // Opened from a tile, a notification or a cast hand-back,')
end=s.index('        // Top-level actions (投屏/更多)',start)
s=s[:start]+'''        PlayerResumeNotice(
            notice = resumeNotice,
            onRestart = { resumeNotice.dismiss(); latestOnSeek(0L) },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 120.dp),
        )

'''+s[end:]
anchor='    val remotePanel ='
ix=s.index(anchor)
s=s[:ix]+'''    val resumeNotice = rememberResumeNotice(
        resumedFromMs = resumedFromMs,
        itemIndex = state.currentIndex,
        ready = state.playing && !state.buffering && state.error == null,
        controlsVisible = visible,
        interrupted = interactions > 0 || locked || watchLocked || settingsPanelKind != null ||
            quickPopup != null || drawerOpen || watchChatOpen || danmakuSearchOpen || danmakuSendOpen ||
            gestureHelpOpen || watchDialogOpen || state.error != null,
    )

'''+s[ix:]
write(p,s)
write(C+'feature/player/PlayerResumeNotice.kt','''package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal const val RESUME_NOTICE_MS = 3_000L

internal class ResumeNoticeState(val initialIndex: Int, val eligible: Boolean) {
    var dismissed by mutableStateOf(false)
        private set
    private var shown by mutableStateOf(false)
    val visible: Boolean get() = eligible && shown && !dismissed

    fun show() { if (!dismissed) shown = true }
    fun dismiss() { dismissed = true }
}

@Composable
internal fun rememberResumeNotice(
    resumedFromMs: Long?,
    itemIndex: Int,
    ready: Boolean,
    controlsVisible: Boolean,
    interrupted: Boolean,
): ResumeNoticeState {
    val notice = remember { ResumeNoticeState(itemIndex, resumedFromMs != null && resumedFromMs > 0L) }
    val latestReady by rememberUpdatedState(ready)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice, itemIndex, controlsVisible, interrupted) {
        if (itemIndex != notice.initialIndex || !controlsVisible || interrupted) notice.dismiss()
    }
    LaunchedEffect(notice) {
        if (!notice.eligible) return@LaunchedEffect
        // Initial buffering consumes no reading time. Later buffer ticks cannot restart this timer.
        snapshotFlow { latestReady }.first { it }
        if (notice.dismissed) return@LaunchedEffect
        notice.show()
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            RESUME_NOTICE_MS, containsIcons = false, containsText = true, containsControls = true,
        ) ?: RESUME_NOTICE_MS
        if (timeout != Long.MAX_VALUE) {
            delay(timeout)
            notice.dismiss()
        }
    }
    return notice
}

/** Quiet edge action, with a full touch target but no bright button or opaque card over the film. */
@Composable
internal fun PlayerResumeNotice(notice: ResumeNoticeState, onRestart: () -> Unit, modifier: Modifier) {
    ChromeVisibility(notice.visible, modifier = modifier, edge = ChromeEdge.Bottom) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("已续播", style = AppTypography.caption.medium, color = Color.White.copy(alpha = 0.55f))
            Text("·", style = AppTypography.caption.medium, color = Color.White.copy(alpha = 0.4f))
            Text(
                "从头播放",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.pressable(onClickLabel = "从头播放", onClick = onRestart)
                    .touchTarget().padding(horizontal = 6.dp),
            )
        }
    }
}
''')
# Behavioral lifecycle test uses the production composable without a UI or a device.
s=read(T+'feature/player/PlayerControlSnapshotTest.kt')
fixture=s[s.index('    private class NoNodes'):s.rfind('}')]
write(T+'feature/player/ResumeNoticeTest.kt','''package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResumeNoticeTest {
    @Test
    fun buffering_does_not_consume_the_three_second_offer_and_reopening_controls_does_not_replay_it() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val ready = mutableStateOf(false)
        val controls = mutableStateOf(true)
        val interrupted = mutableStateOf(false)
        lateinit var notice: ResumeNoticeState
        var nanos = 0L
        fun settle() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                runCurrent()
            }
        }
        try {
            composition.setContent {
                notice = rememberResumeNotice(60_000L, 0, ready.value, controls.value, interrupted.value)
            }
            settle()
            advanceTimeBy(10_000L)
            settle()
            assertFalse(notice.visible)
            ready.value = true
            settle()
            assertTrue(notice.visible)
            advanceTimeBy(2_000L)
            ready.value = false
            settle()
            ready.value = true
            settle()
            advanceTimeBy(1_001L)
            settle()
            assertFalse(notice.visible)
            controls.value = false
            settle()
            controls.value = true
            settle()
            assertFalse(notice.visible)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

    @Test
    fun_dismissed_offer_cannot_be_revived_and_new_playback_has_no_offer() {
        val notice = ResumeNoticeState(0, true)
        notice.dismiss()
        notice.show()
        assertFalse(notice.visible)
        val fresh = ResumeNoticeState(0, false)
        fresh.show()
        assertFalse(fresh.visible)
    }

'''.replace('    fun_dismissed_offer', '    fun dismissed_offer')+fixture+'}\n')
