package com.yfuse.core2.android

internal enum class AndroidPausedPreviewState {
    Idle,
    Decoding,
    AwaitingCallback,
    SubmittedUnconfirmed,
    Rendered,
}

/** Decodes one seek frame while paused, then restores the shared A/V source position on resume. */
internal class AndroidPausedVideoPreview {
    private var resumePositionUs: Long? = null
    private var submittedAtNs: Long? = null
    val resumePending: Boolean get() = resumePositionUs != null
    var state = AndroidPausedPreviewState.Idle
        private set
    val active: Boolean
        get() = state == AndroidPausedPreviewState.Decoding || state == AndroidPausedPreviewState.AwaitingCallback
    val submitted: Boolean
        get() = state != AndroidPausedPreviewState.Idle && state != AndroidPausedPreviewState.Decoding
    val submittedUnconfirmed: Boolean get() = state == AndroidPausedPreviewState.SubmittedUnconfirmed

    fun begin(positionUs: Long) {
        resumePositionUs = positionUs
        state = AndroidPausedPreviewState.Decoding
        submittedAtNs = null
    }

    fun frameSubmitted(nowNs: Long = System.nanoTime()) {
        if (active && !submitted) {
            state = AndroidPausedPreviewState.AwaitingCallback
            submittedAtNs = nowNs
        }
    }

    fun frameRendered() {
        if (submitted) state = AndroidPausedPreviewState.Rendered
    }

    /** Keep codec output ownership moving while the one displayed frame awaits its render fence. */
    fun recycleSubmittedOutput(
        dequeue: () -> YCodecOutputResult,
        discard: (YCodecOutputResult.Buffer) -> Unit,
    ): Boolean {
        if (!active || !submitted) return false
        return when (val output = dequeue()) {
            YCodecOutputResult.TryAgain -> false
            is YCodecOutputResult.FormatChanged -> true
            is YCodecOutputResult.Buffer -> {
                discard(output)
                true
            }
        }
    }

    fun takeResumePosition(): Long? = resumePositionUs.also { clear() }

    /** Older Android releases can omit a static frame's callback. Its submission is never render proof. */
    fun finishCallbackWait(nowNs: Long = System.nanoTime()): Boolean {
        if (state != AndroidPausedPreviewState.AwaitingCallback ||
            submittedAtNs?.let { nowNs - it >= 1_000_000_000L } != true
        ) {
            return false
        }
        state = AndroidPausedPreviewState.SubmittedUnconfirmed
        // Keep the resume position and the player's render epoch alive for Play or a late callback.
        return true
    }

    fun clear() {
        resumePositionUs = null
        state = AndroidPausedPreviewState.Idle
        submittedAtNs = null
    }
}
