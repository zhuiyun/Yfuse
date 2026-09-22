package com.yfuse.feature.player

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** Cancellation stops the waiter, but only the external callback releases the copy lane. */
internal class AmbientCopyQueue {
    private val lane = Semaphore(1)

    suspend fun <T, R> copy(
        create: () -> T,
        request: (T, (Boolean) -> Unit) -> Unit,
        read: (T) -> R,
        release: (T) -> Unit,
    ): R? {
        lane.acquire()
        val destination = try {
            currentCoroutineContext().ensureActive()
            create()
        } catch (error: Throwable) {
            lane.release()
            throw error
        }
        return awaitAmbientCopy(destination, request, read) {
            try {
                release(it)
            } finally {
                lane.release()
            }
        }
    }
}

/** Each asynchronous copy owns its destination until its callback, even after cancellation. */
internal suspend fun <T, R> awaitAmbientCopy(
    destination: T,
    request: (T, (Boolean) -> Unit) -> Unit,
    read: (T) -> R,
    release: (T) -> Unit,
): R? = suspendCancellableCoroutine { continuation ->
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
    if (!letterboxed || picture == null || viewSize.width <= 0 || viewSize.height <= 0 ||
        bufferSize.width <= 0 || bufferSize.height <= 0
    ) return null
    if (picture.width <= 0 || picture.height <= 0 || picture.left < 0 || picture.top < 0 ||
        picture.right > viewSize.width || picture.bottom > viewSize.height
    ) return null
    fun x(value: Int) = (value.toDouble() * bufferSize.width / viewSize.width).roundToInt()
    fun y(value: Int) = (value.toDouble() * bufferSize.height / viewSize.height).roundToInt()
    return IntRect(x(picture.left), y(picture.top), x(picture.right), y(picture.bottom))
        .takeIf { it.width > 0 && it.height > 0 }
}
