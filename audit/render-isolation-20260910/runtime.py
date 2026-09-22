from edit import read,write
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
def imports(p,*names):
    s=read(p)
    for n in names:
        if 'import '+n+'\n' not in s:s=s.replace('\n\nimport ','\n\nimport '+n+'\nimport ',1)
    write(p,s)

write(C+'feature/player/PlaybackRuntimeProjection.kt','''package com.yfuse.feature.player

/** Routing/controls subscribe to state transitions; clocks and telemetry keep their own live source. */
internal fun PlaybackState.runtimeProjection(): PlaybackState = copy(
    positionMs = if (positionMs > 0L) 1L else 0L,
    bufferedPositionMs = 0L,
    diagnostics = diagnostics.copy(
        playbackHealth = "", powerProfile = "", resourcePressure = "", performanceBaseline = "",
        bitrateBitsPerSecond = 0L, frameRate = 0f, droppedFrames = 0, avSyncOffsetMs = null,
        avSyncMeasurement = "", bufferedDurationMs = 0L, bufferEvents = 0,
        rebufferDurationMs = 0L, longestRebufferMs = 0L, networkBitsPerSecond = 0L,
        sourceQueueBytes = 0L, sourceBufferedMs = 0L, sourceStarvationCount = 0L,
        outputEvidence = diagnostics.outputEvidence.copy(audioUnderrunCount = 0, mistimedFrameCount = 0),
    ),
)
''')
p=C+'feature/player/PlaybackRuntimeContent.kt'
write(p,'''package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.StateFlow

/** Timeline stabilization runs in the collector; only structural transitions restart the runtime tree. */
@Composable
internal fun PlaybackRuntimeContent(
    owner: Any,
    source: StateFlow<PlaybackState>,
    items: List<PlayerMediaItem>,
    content: @Composable (PlaybackState, State<PlaybackState>) -> Unit,
) {
    val live = remember { mutableStateOf(source.value) }
    val latestItems = rememberUpdatedState(items)
    val memory = remember { arrayOf(PlaybackTimelineMemory()) }
    LaunchedEffect(owner, source) {
        source.collect { reported ->
            val item = latestItems.value.getOrNull(reported.currentIndex)
            val resolution = stabilizePlaybackTimeline(
                memory = memory[0],
                media = item?.let { PlaybackTimelineIdentity(reported.currentIndex, it.serverId, it.id) },
                reported = reported,
            )
            memory[0] = resolution.memory
            live.value = resolution.state
        }
    }
    val runtime = remember(live) { derivedStateOf { live.value.runtimeProjection() } }
    content(runtime.value, live)
}
''')
# A small binding may observe every tick without invalidating its caller.
p=A+'feature/player/PlaybackNetworkRecoveryBinding.kt'
imports(p,'androidx.compose.runtime.State')
s=read(p).replace('localState: PlaybackState,','localPlayback: State<PlaybackState>,')
s=s.replace(') {\n    LaunchedEffect(',') {\n    val localState = localPlayback.value\n    LaunchedEffect(',1);write(p,s)

# Actions read the position at the instant of the operation, including after a long-open panel.
for rel in ['PlaybackBookmarkBinding.kt','PlayerDanmakuCoordinator.kt']:
    p=A+'feature/player/'+rel;s=read(p).replace('positionMs: Long,','positionMs: () -> Long,')
    if rel=='PlaybackBookmarkBinding.kt':s=s.replace('positionMs.coerceAtLeast(0)', 'positionMs().coerceAtLeast(0)')
    else:s=s.replace('positionMs = positionMs,','positionMs = positionMs(),')
    write(p,s)

# Segment membership is a low-frequency derived state; forward-progress observation stays in a collector.
p=C+'feature/player/PlayerSkipCoordinator.kt'
imports(p,'androidx.compose.runtime.State','androidx.compose.runtime.derivedStateOf','androidx.compose.runtime.snapshotFlow')
s=read(p).replace('playbackState: PlaybackState,','playback: State<PlaybackState>,')
s=s.replace('): PlayerSkipController {', '''): PlayerSkipController {
    val playbackState by remember(playback) { derivedStateOf { playback.value.runtimeProjection() } }''',1)
s=s.replace('val activeSegment = segments.firstOrNull { it.contains(playbackState.positionMs, playbackState.durationMs) }', '''val activeSegment by remember(segments, playback) {
        derivedStateOf { playback.value.let { current -> segments.firstOrNull { it.contains(current.positionMs, current.durationMs) } } }
    }''')
start=s.index('    LaunchedEffect(\n        currentItem?.id,\n        activeSegment?.type,');end=s.index('    val playbackReady = playbackState.playing',start)
block=s[start:end]
# Find the complete effect rather than the playbackReady local inside it.
end=s.index('\n    val playbackReady = playbackState.playing', start)
s=s[:start]+'''    LaunchedEffect(currentItem?.id, segments, playback) {
        snapshotFlow { playback.value }.collect { current ->
            if (currentItem != null) {
                val active = segments.firstOrNull { it.contains(current.positionMs, current.durationMs) }
                val ready = current.playing && !current.buffering
                if (observedForwardPlaybackOutsideCredits(
                        previousPositionMs = lastOutsideCreditsPositionMs,
                        positionMs = current.positionMs, segmentType = active?.type, playbackReady = ready,
                    )) creditsEnteredFromPlayback = true
                if (ready && active?.type != PlaybackSegmentType.Credits) lastOutsideCreditsPositionMs = current.positionMs
            }
        }
    }
'''+s[end:];write(p,s)

# Keep full runtime observations for logging/health decisions, expose a State so telemetry doesn't restart the caller.
p=A+'feature/player/YCorePlayerRuntime.android.kt'
imports(p,'androidx.compose.runtime.State')
s=read(p).replace('internal fun rememberYCoreRuntimeAssessment(', 'internal fun rememberYCoreRuntimeAssessmentState(')
s=s.replace('    state: PlaybackState,\n    networkRecoveryAttempts:', '    state: PlaybackState,\n    stateSource: State<PlaybackState>,\n    networkRecoveryAttempts:')
s=s.replace('): YCoreRuntimeAssessment {', '): State<YCoreRuntimeAssessment> {',1)
s=s.replace('var assessment by remember(session) { mutableStateOf(session.initialAssessment) }', 'val assessment = remember(session) { mutableStateOf(session.initialAssessment) }')
s=s.replace('val latestState by rememberUpdatedState(state)','val latestState by stateSource')
s=s.replace('            assessment = observed','            assessment.value = observed')
# Initial counters come from the live source, not the UI projection.
s=s.replace('initialPositionMs = state.positionMs', 'initialPositionMs = stateSource.value.positionMs').replace('initialBufferEvents = state.diagnostics.bufferEvents','initialBufferEvents = stateSource.value.diagnostics.bufferEvents').replace('initialDroppedFrames = state.diagnostics.droppedFrames','initialDroppedFrames = stateSource.value.diagnostics.droppedFrames')
write(p,s)

p=A+'feature/player/PlayerRoot.kt'
imports(p,'androidx.compose.runtime.derivedStateOf')
s=read(p);start=s.index('    PlaybackRuntimeContent(owner = engine, source = presentationState)');end=s.index('        LaunchedEffect(\n            engine,\n            localState.error,',start)
s=s[:start]+'''    PlaybackRuntimeContent(owner = engine, source = presentationState, items = items) { localState, liveLocalState ->
        val latestLocalState by liveLocalState
'''+s[end:]
s=s.replace('val castState by castManager.state.collectAsState()', '''val liveCastState = castManager.state.collectAsState()
        val castState by remember(liveCastState) { derivedStateOf { liveCastState.value.copy(positionMs = 0L) } }''')
start=s.index('        val baseState =');end=s.index('        BindPlaybackNetworkRecovery(',start)
s=s[:start]+s[end:]
s=s.replace('''            player,
            localState,
            castAuthoritative,''','''            player,
            liveLocalState,
            castAuthoritative,''',1)
s=s.replace('''        val runtimeAssessment =
            rememberYCoreRuntimeAssessment(''','''        val runtimeAssessmentState =
            rememberYCoreRuntimeAssessmentState(''',1)
s=s.replace('                state = localState,\n                networkRecoveryAttempts', '                state = localState,\n                stateSource = liveLocalState,\n                networkRecoveryAttempts',1)
marker='        LaunchedEffect(activeProbe.probeDepth, activeProbe.capabilitySignature)'
s=s.replace(marker,'''        val runtimeAssessment by remember(runtimeAssessmentState) {
            derivedStateOf {
                val current = runtimeAssessmentState.value
                current.copy(health = current.health.copy(
                    observedPlaybackMs = 0L, droppedFrames = current.health.droppedFrames / 10 * 10,
                    droppedFramesPerMinute = 0f,
                ), reportHealth = false)
            }
        }
'''+marker,1)
start=s.index('        val state =\n            baseState.copy(');end=s.index('        val hdrPresentation',start)
metadata=s[start:end];metadata=metadata[metadata.index('            baseState.copy('):]
metadata=metadata.replace('runtimeAssessment.', 'assessment.').replace('baseState.copy(', 'base.copy(').replace('baseState.diagnostics', 'base.diagnostics')
s=s[:start]+'''        val metadataSource = rememberUpdatedState<(PlaybackState) -> PlaybackState> { base ->
            val assessment = runtimeAssessmentState.value
'''+metadata+'''        }
        val livePlayback = remember(liveLocalState, liveCastState, castAuthoritative, castPlayMethod) {
            derivedStateOf {
                val local = liveLocalState.value
                val base = if (castAuthoritative) local.withRemoteCast(liveCastState.value, castPlayMethod) else local
                metadataSource.value(base)
            }
        }
        val state by remember(livePlayback) { derivedStateOf { livePlayback.value.runtimeProjection() } }
'''+s[end:]
# The timer's arming boundary needs each real sample, not a routing snapshot.
start=s.index('        LaunchedEffect(\n            sleepTimerOption,\n            sleepTimerEndIndex,\n            localState.currentIndex,\n            localState.positionMs,');end=s.index('        LaunchedEffect(',start+25)
s=s[:start]+'''        LaunchedEffect(sleepTimerOption, sleepTimerEndIndex, liveLocalState) {
            snapshotFlow { liveLocalState.value }.collect { current ->
                if (sleepTimerOption == SleepTimerOption.EndOfEpisode && sleepTimerEndIndex == current.currentIndex &&
                    current.durationMs > 0L && current.remainingMs <= END_OF_EPISODE_ARM_WINDOW_MS) {
                    sleepTimerArmedItemReachedEnd = true
                }
            }
        }
'''+s[end:]
s=s.replace('fallbackPositionMs = localState.positionMs','fallbackPositionMs = liveLocalState.value.positionMs')
s=s.replace('rememberPlaybackBookmarkBinding(playbackPreferences, currentItem, state.positionMs)', 'rememberPlaybackBookmarkBinding(playbackPreferences, currentItem) { livePlayback.value.positionMs }')
s=s.replace('positionMs = state.positionMs,\n                preferences = danmakuPreferences', 'positionMs = { livePlayback.value.positionMs },\n                preferences = danmakuPreferences')
s=s.replace('playbackState = state,\n                preferences = skipSegmentPreferences','playback = livePlayback,\n                preferences = skipSegmentPreferences')
s=s.replace('playbackState = rememberUpdatedState(state)', 'playbackState = livePlayback')
s=s.replace('val latestState by rememberUpdatedState(state)', 'val latestState by livePlayback')
s=s.replace('rememberUpdatedState(localState),\n            rememberUpdatedState(castState),','liveLocalState,\n            liveCastState,')
s=s.replace('rememberUpdatedState(state),\n            playbackGate,','livePlayback,\n            playbackGate,')
s=s.replace('BindPlaybackDiagnostics(rememberUpdatedState(state),', 'BindPlaybackDiagnostics(livePlayback,')
s=s.replace('playback = rememberUpdatedState(state),','playback = livePlayback,')
# Read raw positions/measurements inside callbacks, never capture the routing projection.
s=s.replace('loadCastItem(deviceId, state.currentIndex, state.positionMs)', 'loadCastItem(deviceId, state.currentIndex, livePlayback.value.positionMs)')
s=s.replace('positionMs = state.positionMs,', 'positionMs = livePlayback.value.positionMs,')
s=s.replace('state.diagnostics.avSyncOffsetMs?.let { measured ->', 'livePlayback.value.diagnostics.avSyncOffsetMs?.let { measured ->')
s=s.replace('                                    localState.positionMs','                                    liveLocalState.value.positionMs')
# The small picture/status/danmaku subtree gets a live timeline; the large controls/callback tree gets State.
start=s.index('            PlaybackContinuityOverlay(');end=s.index('            AnimatedVisibility(\n                visible = !inPictureInPicture',start)
chunk=s[start:end].replace('positionMs = livePlayback.value.positionMs,','positionMs = state.positionMs,')
s=s[:start]+'            PlaybackTimelineContent(livePlayback) { state ->\n'+chunk+'            }\n\n'+s[end:]
write(p,s)

# The controls' menu snapshot also excludes polling telemetry; diagnostics get their live state at the panel boundary.
p=C+'feature/player/PlayerControlSnapshot.kt';s=read(p)
s=s.replace('value.copy(positionMs = if (value.positionMs > 0L) 1L else 0L, bufferedPositionMs = 0L)','value.runtimeProjection()');write(p,s)
