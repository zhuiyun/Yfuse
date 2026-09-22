package com.yfuse.feature.player

/** A configured force-window placeholder is not evidence that the selected media has rendered. */
internal class MpvVideoReadinessGate {
    private enum class Phase { AwaitingStart, Loading, Loaded, Started }

    private var phase = Phase.AwaitingStart

    @Synchronized
    fun onLoadRequested() {
        phase = Phase.AwaitingStart
    }

    @Synchronized
    fun onStartFile() {
        phase = Phase.Loading
    }

    @Synchronized
    fun onFileLoaded() {
        if (phase == Phase.Loading) phase = Phase.Loaded
    }

    @Synchronized
    fun onPlaybackRestart() {
        if (phase == Phase.Loaded) phase = Phase.Started
    }

    @Synchronized
    fun canReportRendering(
        outputConfigured: Boolean,
        surfaceValid: Boolean,
    ): Boolean = phase == Phase.Started && outputConfigured && surfaceValid
}
