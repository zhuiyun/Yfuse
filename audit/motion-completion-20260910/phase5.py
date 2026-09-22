from edit import read, write, replace
import textwrap
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
p=A+'feature/player/PlayerRoot.kt'; s=read(p)
imports=s[s.index('import '):s.index('\n\n',s.index('import '))]
start=s.index('        var networkRecoveryAttempts by')
end=s.index('        var longBufferRecoveryAttempts by',start)
s=s[:start]+'''        val networkRecovery = remember(localCastItem?.serverId, localCastItem?.id, localCastItem?.versionId) {
            PlaybackNetworkRecoveryState()
        }
'''+s[end:]
start=s.index('        val networkRecoveryController =')
end=s.index('        val deviceCapabilityLabel =',start)
effects=s[s.index('        LaunchedEffect(',start):end]
mapping={'networkRecoveryAttempts':'attempts','networkRecoverySuccesses':'successes','networkRecoveryPending':'pending','networkRecoveryResumePositionMs':'resumePositionMs','networkRecoveryController':'controller'}
for old,new in mapping.items(): effects=effects.replace(old,'recovery.'+new)
write(A+'feature/player/PlaybackNetworkRecoveryBinding.kt', 'package com.yfuse.feature.player\n\n'+imports+'''
import com.yfuse.core.data.PlaybackNetworkClass

/** Item/version ownership remains outside engine replacement so an in-flight recovery survives handover. */
internal class PlaybackNetworkRecoveryState {
    val controller = PlaybackNetworkRecoveryController()
    var attempts by mutableIntStateOf(0)
    var successes by mutableIntStateOf(0)
    var pending by mutableStateOf(false)
    var resumePositionMs by mutableStateOf<Long?>(null)
}

@Composable
internal fun BindPlaybackNetworkRecovery(
    recovery: PlaybackNetworkRecoveryState,
    playbackNetworkClass: PlaybackNetworkClass,
    engine: VideoEngine,
    player: YPlayer,
    localState: PlaybackState,
    castAuthoritative: Boolean,
    attachedEngineLabel: String,
) {
'''+textwrap.indent(textwrap.dedent(effects),'    ')+'}\n')
s=s[:start]+'''        BindPlaybackNetworkRecovery(
            networkRecovery, playbackNetworkClass, engine, player, localState, castAuthoritative, attachedEngineLabel,
        )
'''+s[end:]
# Preserve named diagnostic parameters; replace only expression references and assignments.
import re
for old,new in list(mapping.items())[:4]:
    s=re.sub(r'\b'+old+r'\b(?!\s*=\s*'+old+r'\b)', 'networkRecovery.'+new,s)
# Named parameters were not otherwise references.
s=s.replace('networkRecovery.attempts = networkRecovery.attempts','networkRecoveryAttempts = networkRecovery.attempts')
s=s.replace('networkRecovery.successes = networkRecovery.successes','networkRecoverySuccesses = networkRecovery.successes')
# Queue creation/request payload is independent of runtime composition and sleeps.
start=s.index('            val item = latestActiveItems.getOrNull(index) ?: return false',s.index('        suspend fun loadCastItem('))
end=s.index('            if (!loaded) return false',start)
payload=s[start:end].replace('latestActiveItems','items')
payload=payload.replace('            val loaded =','            return',1)
write(A+'feature/player/PlaybackCastBinding.kt','package com.yfuse.feature.player\n\n'+imports+'''
import com.yfuse.core.cast.CastState

internal suspend fun loadPlaybackCastItem(
    castManager: CastManager,
    items: List<PlayerMediaItem>,
    deviceId: String,
    index: Int,
    positionMs: Long,
): Boolean {
'''+textwrap.indent(textwrap.dedent(payload),'    ')+'''}

/** Mirror receiver queue changes without recreating the binding for position ticks. */
@Composable
internal fun BindCastQueue(
    castState: CastState,
    player: YPlayer,
    items: List<PlayerMediaItem>,
    currentIndex: Int,
) {
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestItems by rememberUpdatedState(items)
    LaunchedEffect(player, castState.sessionRevision, castState.currentQueueIndex,
        castState.queueSize, castState.hasActiveSession) {
        if (castState.hasActiveSession && castState.queueSize > 1 &&
            castState.currentQueueIndex in latestItems.indices && latestIndex != castState.currentQueueIndex) {
            player.selectItem(castState.currentQueueIndex)
            player.pause()
        }
    }
}
''')
s=s[:start]+'''            val loaded = loadPlaybackCastItem(castManager, latestActiveItems, deviceId, index, positionMs)
'''+s[end:]
start=s.index('        LaunchedEffect(\n            castState.sessionRevision,\n            castState.currentQueueIndex,')
end=s.index('        var autoAdvancedCastRevision',start)
s=s[:start]+'''        BindCastQueue(castState, player, activeItems, localState.currentIndex)

'''+s[end:]
# The reporting actor is kept stable while latest playback snapshots are read inside snapshotFlow.
start=s.index('        // One actor owns the entire reporting lifetime.')
end=s.index('        // Last resort of the fallback chain:',start)
body=s[start:end]
body=body.replace('val currentLocal = latestLocalState','val currentLocal = localState.value')
body=body.replace('val currentCast = castState','val currentCast = castState.value')
body=body.replace('completedCastHandoffRevision !=','completedHandoff.value !=')
body=body.replace('latestActiveItems','latestItems.value').replace('reporter?.close(latestState)','reporter?.close(latestState.value)')
write(A+'feature/player/PlaybackReportingBinding.kt','package com.yfuse.feature.player\n\n'+imports+'''
import androidx.compose.runtime.State
import com.yfuse.core.cast.CastState

@Composable
internal fun BindPlaybackReporting(
    engine: VideoEngine,
    castManager: CastManager,
    activeItems: List<PlayerMediaItem>,
    playbackSink: PlaybackEventSink?,
    localState: State<PlaybackState>,
    castState: State<CastState>,
    completedHandoff: State<Long?>,
    latestState: State<PlaybackState>,
    playbackGate: WatchGatedPlayback,
    onPlaybackState: (PlaybackState, PlayerMediaItem?) -> Unit,
) {
    val latestItems = rememberUpdatedState(activeItems)
    val latestCallback by rememberUpdatedState(onPlaybackState)
'''+textwrap.indent(textwrap.dedent(body).replace('onPlaybackState(observedState,','latestCallback(observedState,'),'    ')+'}\n')
s=s[:start]+'''        BindPlaybackReporting(
            engine, castManager, activeItems, playbackSink,
            rememberUpdatedState(localState), rememberUpdatedState(castState),
            rememberUpdatedState(completedCastHandoffRevision), rememberUpdatedState(state),
            playbackGate, onPlaybackState,
        )

'''+s[end:]
write(p,s)
# Only status-bearing fields enter the readiness effect; the reconciliation loop reads its State directly.
p=A+'feature/player/PlayerWatchSyncEffects.kt';s=read(p)
s=s.replace('import androidx.compose.runtime.Composable','import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.State')
s=s.replace('playbackState: PlaybackState,','playbackState: State<PlaybackState>,')
s=s.replace('val latestPlaybackState by rememberUpdatedState(playbackState)','val latestPlaybackState by playbackState')
# Remaining direct uses are readiness-only (not position).
s=s.replace('playbackState.buffering','playbackState.value.buffering').replace('playbackState.error','playbackState.value.error').replace('playbackState.durationMs','playbackState.value.durationMs')
write(p,s)
replace(A+'feature/player/PlayerRoot.kt','playbackState = state,\n            watchState = watchState,','playbackState = rememberUpdatedState(state),\n            watchState = watchState,')
