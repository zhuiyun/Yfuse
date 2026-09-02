package com.yfuse

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.yfuse.core.logging.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/** Runs non-critical process work only after the first activity has had a chance to draw. */
internal class DeferredAppStartup(
    private val application: Application,
    private val initialize: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private val scheduled = AtomicBoolean(false)

    fun register() {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (!scheduled.compareAndSet(false, true)) return
        application.unregisterActivityLifecycleCallbacks(this)
        activity.window.decorView.postOnAnimation {
            activity.window.decorView.postDelayed(
                {
                    runCatching(initialize)
                        .onSuccess {
                            AppLog.info(
                                category = "startup",
                                event = "deferred_initialization_complete",
                                message = "Non-critical application work started after first frame",
                            )
                        }.onFailure { error ->
                            AppLog.error(
                                category = "startup",
                                event = "deferred_initialization_failed",
                                message = "Deferred application initialization failed",
                                throwable = error,
                            )
                        }
                },
                DEFERRED_STARTUP_DELAY_MS,
            )
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Privacy-safe phase timing for cold-start diagnostics and macrobenchmark correlation. */
internal class AppStartupTrace {
    private val startedNs = SystemClock.elapsedRealtimeNanos()
    private var previousNs = startedNs

    fun mark(phase: String) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        AppLog.info(
            category = "startup",
            event = "phase_complete",
            message = "Application startup phase completed",
            attributes =
                mapOf(
                    "phase" to phase,
                    "phaseMs" to ((nowNs - previousNs) / NANOS_PER_MILLISECOND).toString(),
                    "totalMs" to ((nowNs - startedNs) / NANOS_PER_MILLISECOND).toString(),
                ),
        )
        previousNs = nowNs
    }
}

private const val DEFERRED_STARTUP_DELAY_MS = 350L
private const val NANOS_PER_MILLISECOND = 1_000_000L
