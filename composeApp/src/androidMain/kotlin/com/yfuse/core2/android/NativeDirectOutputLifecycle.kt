package com.yfuse.core2.android

/** Output intent survives a media reopen, while established output belongs only to that media. */
internal class NativeDirectOutputLifecycle {
    @Volatile
    var videoEstablished = false
        private set

    var videoSuspended = false
        private set

    private var audioContinuation = false

    var awaitingVideoSync = false
        private set

    var skippedVideoSamples = 0
        private set

    fun setVideoSuspended(
        suspended: Boolean,
        audioCarryingPlayback: Boolean,
    ) {
        // The foreground can arrive before its Surface. Sound that began in the background must
        // carry through that gap and decoder reconstruction, even if no video frame existed yet.
        if (videoSuspended && audioCarryingPlayback) audioContinuation = true
        videoSuspended = suspended
    }

    fun allowsAudioPump(
        hasVideo: Boolean,
        hasAudio: Boolean,
    ): Boolean = hasAudio && (!hasVideo || videoEstablished || videoSuspended || audioContinuation)

    fun videoRendered() {
        videoEstablished = true
    }

    fun awaitVideoSync() {
        awaitingVideoSync = true
        skippedVideoSamples = 0
    }

    fun discardUntilVideoSync(syncSample: Boolean): Boolean {
        if (awaitingVideoSync && !syncSample) {
            skippedVideoSamples++
            return true
        }
        clearVideoSyncWait()
        return false
    }

    fun clearVideoSyncWait() {
        awaitingVideoSync = false
        skippedVideoSamples = 0
    }

    fun resetMedia() {
        clearVideoSyncWait()
        videoEstablished = false
        audioContinuation = false
    }
}
