from edit import *

p='composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt'
t=read(p)
start=t.index('    LaunchedEffect(\n        localCastItem?.id,');end=t.index('    val runtimeAssessment =',start)
effect=t[start:end]
write('composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerDiagnosticBinding.kt', '''package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackPlan
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingDiagnostic(
    val state: PlaybackState,
    val kind: PlayerEngine,
    val fallback: List<PlayerEngine>,
    val nativeOnly: Boolean,
)

/** A single worker owns expensive report construction; a slow writer retains only the latest tick. */
@Composable
internal fun BindPlaybackDiagnostics(state: PlaybackState, kind: PlayerEngine, enginesTried: Set<PlayerEngine>, nativeOnly: Boolean) {
    val pending = remember { Channel<PendingDiagnostic>(Channel.CONFLATED) }
    DisposableEffect(pending) {
        val lifetime = SupervisorJob()
        CoroutineScope(lifetime + Dispatchers.IO).launch {
            try {
                for (snapshot in pending) {
                    try {
                        PlaybackDiagnosticReportRegistry.update(snapshot.state, snapshot.kind, snapshot.fallback, snapshot.nativeOnly)
                    } catch (error: Exception) {
                        AppLog.warning("player.diagnostics", "report_failed", "Playback diagnostic report could not be saved", throwable = error)
                    }
                }
            } finally {
                lifetime.complete()
            }
        }
        // Drain the final queued state without keeping a worker alive after leaving playback.
        onDispose { pending.close() }
    }
    val fallback = remember(enginesTried, kind) { (enginesTried + kind).toList() }
    SideEffect { pending.trySend(PendingDiagnostic(state, kind, fallback, nativeOnly)) }
}

@Composable
internal fun RecordPlaybackSourceRoute(
    localCastItem: PlayerMediaItem?,
    localState: PlaybackState,
    activeProbe: PlaybackMediaProbe,
    activePlan: PlaybackPlan,
    kind: PlayerEngine,
    effectiveDecoderMode: DecoderMode,
    castAuthoritative: Boolean,
    attachedEngineLabel: String,
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities,
    allowAudioPassthrough: Boolean,
) {
'''+effect.replace('        val version =', '        withContext(Dispatchers.IO) {\n        val version =').rstrip()+'\n        }\n}\n')
# The last two braces close withContext and LaunchedEffect; lexical nesting is equivalent.
t=t[:start]+'''    RecordPlaybackSourceRoute(
        localCastItem, localState, activeProbe, activePlan, kind, effectiveDecoderMode,
        castAuthoritative, attachedEngineLabel, dolbyVisionRuntime, allowAudioPassthrough,
    )
'''+t[end:]
start=t.index('    SideEffect {\n        PlaybackDiagnosticReportRegistry.update(');end=t.index('    var serversTried',start)
t=t[:start]+'''    BindPlaybackDiagnostics(state, kind, enginesTried, core2NativeOnlyActive)
'''+t[end:]
write(p,t)

# Keep this first frame diagnostic safe if export races with the asynchronous native monitor bootstrap.
p='composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AndroidNativeCrashMonitor.kt'
t=read(p)
print('native summary:', t[t.index('    fun diagnosticSummary'):t.index('    fun diagnosticSummary')+350])
