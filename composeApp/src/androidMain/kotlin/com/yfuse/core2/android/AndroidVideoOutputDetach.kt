package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YPlayer
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A player whose decoder renders into the video Surface from a worker of its own.
 *
 * `setVideoOutput(null)` only queues the detach, and the codec keeps releasing output buffers for
 * rendering until the worker gets to it. `SurfaceHolder.Callback.surfaceDestroyed` must not return
 * while that can still happen - the Surface is gone right after - so the view waits for [detached].
 */
internal interface AndroidVideoOutputDetach {
    /** Detaches the output; completes [detached] once nothing this player owns can render into it. */
    fun detachVideoOutput(detached: CompletableDeferred<Unit>)
}

/** Long enough for an idle worker to let go; well short of an input-dispatch ANR. */
internal const val VIDEO_OUTPUT_DETACH_WAIT_MS = 500L

/**
 * Detaches [player]'s video output and blocks the calling (main) thread until its decoder has let
 * go of the Surface, for at most [timeoutMs]. A worker busy elsewhere - a route being rebuilt -
 * must not hold the UI thread hostage, so after the timeout the caller proceeds as it always did
 * and the wait is only logged. Returns whether the detach was confirmed.
 */
internal fun detachVideoOutputAwaiting(
    player: YPlayer,
    timeoutMs: Long = VIDEO_OUTPUT_DETACH_WAIT_MS,
): Boolean {
    if (player !is AndroidVideoOutputDetach) {
        player.setVideoOutput(null)
        return true
    }
    val detached = CompletableDeferred<Unit>()
    val signalled = CountDownLatch(1)
    detached.invokeOnCompletion { signalled.countDown() }
    player.detachVideoOutput(detached)
    val confirmed =
        try {
            signalled.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    if (!confirmed) {
        AppLog.warning(
            category = "player.core2",
            event = "video_output_detach_unconfirmed",
            message = "The decoder had not released the Surface when it was destroyed",
            attributes = mapOf("waitMs" to timeoutMs.toString()),
        )
    }
    return confirmed
}
