from edit import *

p='composeApp/src/androidMain/kotlin/com/yfuse/core/security/ServerSessionRecovery.android.kt'
t=read(p)
t=t.replace('import androidx.compose.runtime.mutableIntStateOf\n','').replace('import androidx.compose.runtime.mutableStateOf\n','').replace('import androidx.compose.runtime.setValue\n','')
t=t.replace('import com.yfuse.core.data.ServerSessionStartup\nimport kotlinx.coroutines.delay', '''import com.yfuse.core.data.AsyncServerSessionStartup
import com.yfuse.core.data.SessionStartupPhase
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel''')
t=t.replace('    private var startup: ServerSessionStartup? = null', '    private var startup: AsyncServerSessionStartup? = null\n    private var startupScope: CoroutineScope? = null')
t=t.replace('        startup = ServerSessionStartup(restore, startServices).also { it.start() }', '''        startupScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startupScope = scope
        startup = AsyncServerSessionStartup(scope, restore, startServices, Dispatchers.IO).also { it.start() }''')
t=t.replace('startup?.takeUnless { it.ready }', 'startup?.takeUnless { it.phase.value == SessionStartupPhase.Ready }')
start=t.index('            var attempt by remember');end=t.index('            MaterialTheme(',start)
t=t[:start]+'''            val phase by pending.phase.collectAsState()
            val restoring = phase == SessionStartupPhase.Restoring
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(phase) {
                when (phase) {
                    SessionStartupPhase.Ready -> activity.recreate()
                    SessionStartupPhase.NeedsRetry -> retryFocus.requestFocus()
                    SessionStartupPhase.Restoring -> Unit
                }
            }
'''+t[end:]
t=t.replace('onClick = { attempt++ }', 'onClick = pending::start')
write(p,t)
for p,app in [('composeApp/src/androidMain/kotlin/com/yfuse/YfuseApp.kt','YfuseApp'),('tvApp/src/androidMain/kotlin/com/yfuse/tv/TvApplication.kt','TvApplication')]:
    replace(p, 'feedCacheSettings = androidFeedCacheSettings(this),', f'feedCacheSettings = androidFeedCacheSettings(this@{app}),')
    replace(p, '        AndroidNativeCrashMonitor.initialize(this)\n', '')
    replace(p, 'restore = { koinApplication.koin.get<ServerRegistry>() },', f'''restore = {{
                // Classify historical crashes before any native engine can be constructed.
                AndroidNativeCrashMonitor.initialize(this@{app})
                koinApplication.koin.get<ServerRegistry>()
            }},''')
# Let the first resumed Activity register the deferred callback even when secure restore completes later.
p='composeApp/src/androidMain/kotlin/com/yfuse/YfuseApp.kt'
t=read(p)
start=t.index('                koinApplication.koin.get<AccountRepository>().start()')
end=t.index('\n            },\n        )',start)
t=t[:start]+'''                startupTrace.mark("session_restore")
                koinApplication.koin.get<AccountRepository>().start()
                koinApplication.koin.get<PlaybackSyncManager>().start()
'''+t[end:]
# Register before any Activity resumes; keep account session semantics, defer only outbox/work scheduling.
marker='        ServerSessionRecovery.initialize('
t=t.replace(marker, '''        DeferredAppStartup(this) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                ServerSessionRecovery.awaitReady()
                koinApplication.koin.get<PlaybackReportingCoordinator>().flushPending()
                scheduleCalendarReminderWork(this@YfuseApp)
                scheduleCalendarSyncWork(this@YfuseApp)
            }
        }.register()
'''+marker)
write(p,t)
p='composeApp/src/androidMain/kotlin/com/yfuse/core/security/ServerSessionRecovery.android.kt'
replace(p, 'import kotlinx.coroutines.cancel', 'import kotlinx.coroutines.cancel\nimport kotlinx.coroutines.flow.first')
replace(p, '    fun showIfNeeded(', '''    suspend fun awaitReady() {
        startup?.phase?.first { it == SessionStartupPhase.Ready }
    }

    fun showIfNeeded(''')

p='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreHttpProxy.kt'
replace(p, '            val socket = runCatching { server.accept() }.getOrNull() ?: break', '''            val socket = try {
                server.accept()
            } catch (error: Exception) {
                if (!closed.get()) {
                    AppLog.error("player.proxy", "accept_failed", "Playback proxy stopped accepting connections unexpectedly", throwable = error)
                }
                break
            }''')
if 'import com.yfuse.core.logging.AppLog' not in read(p):
    replace(p, 'package com.yfuse.core2.android', 'package com.yfuse.core2.android\n\nimport com.yfuse.core.logging.AppLog')

p='composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackEventOutbox.kt'
replace(p, '    val events: List<PlaybackOutboxEvent> = emptyList(),', '    val events: List<PlaybackOutboxEvent> = emptyList(),\n    val droppedTerminalEvents: Long = 0L,')
replace(p, '    val events: StateFlow<List<PlaybackOutboxEvent>> = _events.asStateFlow()', '''    val events: StateFlow<List<PlaybackOutboxEvent>> = _events.asStateFlow()
    private val _droppedTerminalEvents = MutableStateFlow(persisted.droppedTerminalEvents)
    val droppedTerminalEvents: StateFlow<Long> = _droppedTerminalEvents.asStateFlow()

    /** Acknowledge only the loss the user saw, preserving a concurrently arriving warning. */
    fun acknowledgeDroppedReports(observedCount: Long) = synchronized(stateLock) {
        persisted = persisted.copy(droppedTerminalEvents = (persisted.droppedTerminalEvents - observedCount.coerceAtLeast(0L)).coerceAtLeast(0L))
        persistLocked()
    }''')
replace(p, '            val bounded = bound(current)', '''            val bounded = bound(current)
            val lost = current.count { it.kind == PlaybackOutboxEventKind.Stopped } - bounded.count { it.kind == PlaybackOutboxEventKind.Stopped }''')
replace(p, '                    events = bounded,', '                    events = bounded,\n                    droppedTerminalEvents = safeAdd(persisted.droppedTerminalEvents, lost.toLong()),')
replace(p, '        _events.value = persisted.events.sortedBy(PlaybackOutboxEvent::order)', '        _events.value = persisted.events.sortedBy(PlaybackOutboxEvent::order)\n        _droppedTerminalEvents.value = persisted.droppedTerminalEvents')
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackReportingCoordinator.kt'
replace(p, '    private val jobs =', '    private val jobs =') if False else None
# Place public warning API before init, independent of the private queue implementation.
replace(p, '    init {', '''    val droppedTerminalEvents get() = outbox.droppedTerminalEvents
    fun acknowledgeDroppedReports(observedCount: Long) = outbox.acknowledgeDroppedReports(observedCount)

    init {''')
write('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackReportingWarning.kt', '''package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.designsystem.ConfirmDialog

/** Durable warning shown on return to the app; progress playback itself is never interrupted. */
@Composable
fun PlaybackReportingWarning(coordinator: PlaybackReportingCoordinator) {
    val lost by coordinator.droppedTerminalEvents.collectAsState()
    var deferred by remember { mutableLongStateOf(0L) }
    if (lost <= 0L || lost == deferred) return
    ConfirmDialog(
        title = "部分观看进度未同步",
        message = "离线上报队列已满，$lost 条播放结束记录未能保留。对应的服务器观看进度可能不完整。",
        confirmLabel = "知道了",
        dismissLabel = "稍后提醒",
        onConfirm = { coordinator.acknowledgeDroppedReports(lost) },
        onDismiss = { deferred = lost },
    )
}
''')
p='composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt'
replace(p, 'import com.yfuse.feature.player.ActivePlayback', 'import com.yfuse.feature.player.ActivePlayback\nimport com.yfuse.feature.player.PlaybackReportingWarning')
replace(p, '        val reportingCoordinator = root.dependencies.playbackReportingCoordinator', '        val reportingCoordinator = root.dependencies.playbackReportingCoordinator\n        PlaybackReportingWarning(reportingCoordinator)')
p='tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvApp.kt'
replace(p, 'import com.yfuse.feature.player.PlayerScreen', 'import com.yfuse.feature.player.PlayerScreen\nimport com.yfuse.feature.player.PlaybackReportingWarning')
replace(p, '        TvRoot(component)', '        TvRoot(component)\n        PlaybackReportingWarning(component.dependencies.playbackReportingCoordinator)')
