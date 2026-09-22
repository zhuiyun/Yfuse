package com.yfuse.core2.android

import java.util.concurrent.CancellationException

/** A failed optional track choice must not destroy a working playback graph. */
internal inline fun playbackTrackSwitch(
    change: () -> Unit,
    restore: () -> Unit,
    rejected: (Exception) -> Unit,
): Boolean {
    try {
        change()
        return true
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        try {
            restore()
        } catch (rollback: Exception) {
            rollback.addSuppressed(failure)
            throw rollback
        }
        rejected(failure)
        return false
    }
}
