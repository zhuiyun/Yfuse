package com.yfuse.feature.player

/**
 * Persists a unique, connectivity-constrained wake-up for the playback outbox.
 *
 * The common coordinator invokes this after every accepted enqueue and foreground/startup wake.
 * Android supplies WorkManager; keeping the boundary here leaves queue semantics platform-free.
 */
internal expect fun schedulePlaybackOutboxFlush()
