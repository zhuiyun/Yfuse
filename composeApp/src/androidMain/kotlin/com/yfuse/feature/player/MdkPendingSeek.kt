package com.yfuse.feature.player

import java.util.concurrent.atomic.AtomicReference

/** Keeps the newest seek until MDK has loaded the media and accepted the command. */
internal class MdkPendingSeek(
    startPositionMs: Long,
) {
    private class Request(
        val positionMs: Long,
    )

    private val pending = AtomicReference(resumeRequest(startPositionMs))
    val positionMs: Long get() = pending.get()?.positionMs ?: -1L

    /** A stopped or opening native player can report zero before this request has been submitted. */
    fun positionForHandover(
        lastKnownPositionMs: Long,
        loadPending: Boolean = false,
        nativePosition: () -> Long?,
    ): Long =
        pending.get()?.positionMs ?: if (loadPending) {
            lastKnownPositionMs
        } else {
            runCatching(nativePosition).getOrNull() ?: lastKnownPositionMs
        }

    /** A fresh load already starts at zero, so only a nonzero resume point needs seeking. */
    fun reset(positionMs: Long) {
        pending.set(resumeRequest(positionMs))
    }

    /** Explicitly seeking to zero is different from opening a file at its default position. */
    fun request(positionMs: Long) {
        pending.set(Request(positionMs.coerceAtLeast(0L)))
    }

    fun applyIfLoaded(
        loaded: Boolean,
        seek: (Long) -> Unit,
    ): Boolean {
        if (!loaded) return false
        val request = pending.get() ?: return false
        seek(request.positionMs)
        // A newer command received during the native call must survive this completion.
        pending.compareAndSet(request, null)
        return true
    }

    private fun resumeRequest(positionMs: Long): Request? = positionMs.takeIf { it > 0L }?.let(::Request)
}
