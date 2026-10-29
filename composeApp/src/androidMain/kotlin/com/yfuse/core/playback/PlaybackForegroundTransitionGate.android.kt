package com.yfuse.core.playback

import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates start/stop calls that can cross the service's asynchronous creation boundary. */
internal class PlaybackForegroundTransitionGate {
    private val foregroundStarted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    val shouldStop: Boolean
        get() = stopRequested.get()

    fun prepareStart() {
        stopRequested.set(false)
    }

    /** Returns true only when calling Context.stopService is already safe. */
    fun requestStop(): Boolean {
        stopRequested.set(true)
        return foregroundStarted.get()
    }

    fun onForegroundStarted() {
        foregroundStarted.set(true)
    }

    fun onDestroyed() {
        foregroundStarted.set(false)
    }
}
