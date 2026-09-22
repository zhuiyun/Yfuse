package com.yfuse.feature.player

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** A copy whose callback never arrives would otherwise hold the lane, and the light, forever. */
internal const val AMBIENT_COPY_TIMEOUT_MS = 3_000L

/** Cancellation stops the waiter, but only the external callback releases the copy lane. */
internal class AmbientCopyQueue(
    private val timeoutMs: Long = AMBIENT_COPY_TIMEOUT_MS,
) {
    private var lane = Semaphore(1)

    suspend fun <T, R> copy(
        create: () -> T,
        request: (T, (Boolean) -> Unit) -> Unit,
        read: (T) -> R,
        release: (T) -> Unit,
    ): R? {
        val lane = this.lane
        lane.acquire()
        val destination =
            try {
                currentCoroutineContext().ensureActive()
                create()
            } catch (error: Throwable) {
                lane.release()
                throw error
            }
        val outcome =
            withTimeoutOrNull(timeoutMs) {
                Outcome(
                    awaitAmbientCopy(destination, request, read) {
                        try {
                            release(it)
                        } finally {
                            lane.release()
                        }
                    },
                )
            }
        if (outcome == null && this.lane === lane) {
            // Watchdog: leave this lane to the missing callback and open a fresh one. The
            // destination stays owned by the orphaned request until, and unless, it calls back.
            this.lane = Semaphore(1)
        }
        return outcome?.value
    }

    private class Outcome<R>(
        val value: R?,
    )
}

/** Each asynchronous copy owns its destination until its callback, even after cancellation. */
internal suspend fun <T, R> awaitAmbientCopy(
    destination: T,
    request: (T, (Boolean) -> Unit) -> Unit,
    read: (T) -> R,
    release: (T) -> Unit,
): R? =
    suspendCancellableCoroutine { continuation ->
        val complete: (Boolean) -> Unit = { success ->
            try {
                if (continuation.isActive) {
                    continuation.resumeWith(runCatching { if (success) read(destination) else null })
                }
            } finally {
                release(destination)
            }
        }
        try {
            request(destination, complete)
        } catch (_: IllegalArgumentException) {
            // PixelCopy rejects an invalidated source synchronously, without scheduling a callback.
            complete(false)
        }
    }

/** Full-picture surfaces need no crop. Letterboxed surfaces use buffer, not layout, pixels. */
internal fun ambientCopyRect(
    letterboxed: Boolean,
    picture: IntRect?,
    viewSize: IntSize,
    bufferSize: IntSize,
): IntRect? {
    if (!letterboxed ||
        picture == null ||
        viewSize.width <= 0 ||
        viewSize.height <= 0 ||
        bufferSize.width <= 0 ||
        bufferSize.height <= 0
    ) {
        return null
    }
    if (picture.width <= 0 ||
        picture.height <= 0 ||
        picture.left < 0 ||
        picture.top < 0 ||
        picture.right > viewSize.width ||
        picture.bottom > viewSize.height
    ) {
        return null
    }

    fun x(value: Int) = (value.toDouble() * bufferSize.width / viewSize.width).roundToInt()

    fun y(value: Int) = (value.toDouble() * bufferSize.height / viewSize.height).roundToInt()
    return IntRect(x(picture.left), y(picture.top), x(picture.right), y(picture.bottom))
        .takeIf { it.width > 0 && it.height > 0 }
}
