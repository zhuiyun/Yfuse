package com.yfuse

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yfuse.app.RootComponent
import com.yfuse.core.logging.AppLog

/**
 * Keeps non-root model navigation registered during a route's UI callback handoff.
 * Install before setContent so the visible route, menus and predictive animations keep priority.
 */
internal class RootBackFallback(
    private val activity: ComponentActivity,
    private val root: RootComponent,
) {
    private var closed = false
    private var navigationGeneration = 0L
    private var predictiveGeneration: Long? = null
    private val callback =
        object : OnBackPressedCallback(root.backNavigationState().enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                predictiveGeneration = navigationGeneration
            }

            override fun handleOnBackCancelled() {
                predictiveGeneration = null
            }

            override fun handleOnBackPressed() {
                if (closed) return
                val startedGeneration = predictiveGeneration
                predictiveGeneration = null
                // A started gesture must never pop a route pushed or replaced while it was in progress.
                if (startedGeneration != null && startedGeneration != navigationGeneration) {
                    refreshEnabled()
                    return
                }
                val state = root.backNavigationState()
                if (root.navigateBackFromModel()) {
                    AppLog.info(
                        category = "navigation.back",
                        event = "model_fallback",
                        message = "Returned through model navigation while its UI handler was unavailable",
                        attributes = mapOf("tab" to state.tab.name, "depth" to state.depth.toString()),
                    )
                }
                refreshEnabled()
            }
        }
    private val subscriptions =
        root.backNavigationSources().map {
            it.subscribe {
                navigationGeneration++
                refreshEnabled()
            }
        }
    private val lifecycleObserver =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) close()
        }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, callback)
        activity.lifecycle.addObserver(lifecycleObserver)
    }

    private fun refreshEnabled() {
        if (!closed) callback.isEnabled = root.backNavigationState().enabled
    }

    fun close() {
        if (closed) return
        closed = true
        predictiveGeneration = null
        subscriptions.forEach { it.cancel() }
        callback.remove()
        activity.lifecycle.removeObserver(lifecycleObserver)
    }
}
