package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

enum class SessionStartupPhase { Restoring, NeedsRetry, Ready }

/** Restores secure state on a worker; only the owning UI scope starts dependent services. */
class AsyncServerSessionStartup(
    private val scope: CoroutineScope,
    private val restore: () -> Unit,
    private val startServices: () -> Unit,
    private val workContext: CoroutineContext = Dispatchers.Default,
) {
    private val mutablePhase = MutableStateFlow(SessionStartupPhase.Restoring)
    val phase = mutablePhase.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (phase.value == SessionStartupPhase.Ready || job?.isActive == true) return
        mutablePhase.value = SessionStartupPhase.Restoring
        job =
            scope.launch {
                try {
                    withContext(workContext) { restore() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!error.isServerSessionRestoreFailure()) throw error
                    AppLog.warning(
                        "server.registry",
                        "session_restore_deferred",
                        "Saved sessions remain untouched while secure storage is unavailable",
                        throwable = error,
                    )
                    mutablePhase.value = SessionStartupPhase.NeedsRetry
                    return@launch
                }
                startServices()
                mutablePhase.value = SessionStartupPhase.Ready
            }
    }
}
