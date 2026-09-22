from edit import read,write,replace
import textwrap
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
replace(A+'feature/player/PlaybackCastBinding.kt','    return\n    castManager.play(', '    return castManager.play(')
replace(C+'app/App.kt','/** Expanded-width navigation keeps targets compact instead of stretching four across 840dp. */\n\n','')
p=C+'feature/player/PlayerControls.kt';s=read(p)
s=s.replace('import androidx.compose.ui.Modifier','import androidx.compose.ui.Modifier\nimport androidx.compose.ui.geometry.Offset\nimport androidx.compose.runtime.derivedStateOf')
s=s.replace('    state: PlaybackState,','    playback: State<PlaybackState>,',1)
s=s.replace('    var visible by remember { mutableStateOf(true) }','    val state by rememberPlayerControlSnapshot(playback)\n    var visible by remember { mutableStateOf(true) }',1)
s=s.replace('val latestPosition by rememberUpdatedState(state.positionMs)','val latestPosition by remember(playback) { derivedStateOf { playback.value.positionMs } }')
start=s.index('    val timelineState =')
end=s.index('    LaunchedEffect(remoteChromeState?.seekTargetMs',start)
s=s[:start]+s[end:]
# Draw/timeline subtree is the only bottom-bar reader of live position.
start=s.index('            RefinedBottomBar(')
end=s.index('\n        }\n\n        // Auto-skip',start)
body=s[start:end]
body=body.replace('state = timelineState.transportState(),','''state = timelineState.transportState().let { transport ->
                    remoteChromeState?.seekTargetMs?.takeIf { remoteChromeState.seeking }?.let { target ->
                        transport.copy(positionMs = target.coerceIn(0L, transport.durationMs.coerceAtLeast(0L)))
                    } ?: transport
                },''',1)
s=s[:start]+'''            PlaybackTimelineContent(playback) { timelineState ->
'''+textwrap.indent(body,'    ')+'''\n            }'''+s[end:]
start=s.index('        val nextUpRemainingMs =')
end=s.rfind('\n    }\n}')
body=s[start:end]
# Move the countdown subtree (including its visibility check) out of the control composition.
body=body.replace('!nextUpDismissed','!dismissed')
body=body.replace('''Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 96.dp)''','modifier')
body=body.replace('''onPlayNow = {
                    poke()
                    onNextItem()
                },''','onPlayNow = onPlayNow,')
body=body.replace('''onDismiss = {
                    poke()
                    nextUpDismissed = true
                    onDismissNextUp()
                },''','onDismiss = onDismiss,')
write(C+'feature/player/PlayerNextUpOverlay.kt','''package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/** The countdown owns its live timeline subscription, separately from gesture and menu state. */
@Composable
internal fun PlayerNextUpOverlay(
    playback: State<PlaybackState>,
    episodes: List<EpisodeCard>,
    dismissed: Boolean,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val state = playback.value
'''+textwrap.indent(textwrap.dedent(body),'    ')+'\n}\n')
s=s[:start]+'''        PlayerNextUpOverlay(
            playback, episodes, nextUpDismissed,
            onPlayNow = { poke(); onNextItem() },
            onDismiss = { poke(); nextUpDismissed = true; onDismissNextUp() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 96.dp),
        )'''+s[end:]
s=s.replace(' * Everything shown comes from [state]', ' * Everything shown comes from [playback]')
write(p,s)
replace(A+'feature/player/PlayerRoot.kt','PlayerControls(\n                    state = state,','PlayerControls(\n                    playback = rememberUpdatedState(state),')
write(C+'feature/player/PlayerControlSnapshot.kt','''package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** Menus need the started/not-started boundary, not every timeline sample. */
@Composable
internal fun rememberPlayerControlSnapshot(playback: State<PlaybackState>): State<PlaybackState> =
    remember(playback) {
        derivedStateOf {
            val value = playback.value
            value.copy(positionMs = if (value.positionMs > 0L) 1L else 0L, bufferedPositionMs = 0L)
        }
    }

@Composable
internal fun PlaybackTimelineContent(
    playback: State<PlaybackState>,
    content: @Composable (PlaybackState) -> Unit,
) {
    content(playback.value)
}
''')
# Correct fixtures to remain within the real 10-second next-up window.
p='composeApp/src/commonTest/kotlin/com/yfuse/feature/player/NextUpRingStateTest.kt'
s=read(p).replace('12_000','9_000').replace('11_000','8_000')
write(p,s)
# Close remaining finite-motion duration aliases; all loops unrelated to animation keep functional timing.
replace(C+'core/designsystem/Tokens.kt','    const val OLED_PROTECTION = 450','''    const val OLED_PROTECTION = 450
    const val PLAYER_CHROME_STAGGER = 40
    const val WATCH_REACTION = 2_600
    const val STICKER_CLOCK = 60_000''')
replace(C+'feature/player/PlayerChromeTransition.kt','CHROME_STAGGER_MS = 40','CHROME_STAGGER_MS = Motion.PLAYER_CHROME_STAGGER')
for f,old,new in [('WatchReactionOverlay.kt','REACTION_MS = 2_600','REACTION_MS = Motion.WATCH_REACTION'),('WatchStickerTray.kt','SHARED_CLOCK_MS = 60_000','SHARED_CLOCK_MS = Motion.STICKER_CLOCK')]:
    p=C+'feature/player/'+f;s=read(p)
    s=s.replace('import com.yfuse.core.designsystem.LocalAccessibilityOptions','import com.yfuse.core.designsystem.Motion\nimport com.yfuse.core.designsystem.LocalAccessibilityOptions')
    assert old in s
    write(p,s.replace(old,new))
# Clean formatter's awkward line breaks from initially generated one-line branches.
p=C+'core/designsystem/ContentHandoff.kt';s=read(p)
s=s.replace('''if (progress.value <
            1f
        )''','if (progress.value < 1f)')
write(p,s)
p=C+'core/designsystem/SearchDockMotion.kt';s=read(p)
s=s.replace('import androidx.compose.animation.core.Animatable','import androidx.compose.animation.core.Animatable\nimport androidx.compose.animation.core.tween')
s=s.replace('''target !=
            null''','target != null')
s=s.replace('''androidx.compose.animation.core
                    .tween(Motion.MODAL, easing = Motion.Curve)''','tween(Motion.MODAL, easing = Motion.Curve)')
write(p,s)
