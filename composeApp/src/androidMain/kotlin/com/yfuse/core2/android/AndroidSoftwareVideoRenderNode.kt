package com.yfuse.core2.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Process
import android.view.Surface
import com.yfuse.core2.demux.YVideoGeometry
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Completion evidence emitted only after a software frame was posted to the output Surface. */
internal data class YSoftwareRenderSnapshot(
    val renderedFrameCount: Int,
    val presentationTimeUs: Long,
    val renderedRealtimeNs: Long,
    val idle: Boolean,
)

/** Dedicated, bounded Canvas presentation lane for RGBA frames produced by FFmpeg software decode. */
internal class AndroidSoftwareVideoRenderNode {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Native libraries before software decoder API 3 write BGRA. Swapping the channels while
    // drawing costs nothing on a hardware canvas, where a per-frame byte swap would cost a pass
    // over every pixel.
    private val redBlueSwappedPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(redBlueSwapMatrix())
        }
    private val lifecycleLock = Any()
    private var memory: PlaybackMemoryReservation? = null
    private var requestedMemoryBytes = 0L
    private var executor: ExecutorService? = null
    private val frames =
        BoundedFrameLeasePool<Pair<Int, Int>, Bitmap>(
            MAX_IN_FLIGHT_SOFTWARE_FRAMES,
            { (width, height) -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) },
            Bitmap::recycle,
        )

    @Volatile
    private var surface: Surface? = null

    /** The track's pixel shape and rotation, which every frame is drawn squared and turned by. */
    @Volatile
    var geometry: YVideoGeometry = YVideoGeometry()

    private val inFlightFrames = AtomicInteger()
    private val renderedFrames = AtomicInteger()
    private val renderGeneration = AtomicInteger()
    private val lastPresentationTimeUs = AtomicLong(0L)
    private val lastRenderedRealtimeNs = AtomicLong(0L)
    private val failure = AtomicReference<Throwable?>(null)
    private val terminalFailure = AtomicReference<Throwable?>(null)

    fun attach(surface: Surface) {
        require(surface.isValid) { "Software video output Surface is invalid" }
        flush()
        terminalFailure.get()?.let { throw IllegalStateException("Software render lane did not drain", it) }
        this.surface = surface
        failure.set(null)
    }

    /**
     * Copies and queues one frame without waiting for Canvas lock, scale or present.
     *
     * At most two frames (one rendering and one queued) are retained. Returning false asks the
     * decoder lane to keep its reusable FFmpeg buffer until render capacity is available.
     */
    fun tryRender(frame: YSoftwareVideoDecodeResult.Frame): Boolean =
        synchronized(lifecycleLock) {
            throwIfFailed()
            val output = requireNotNull(surface).also { require(it.isValid) }
            val shape = geometry
            require(
                frame.width > 0 &&
                    frame.height > 0 &&
                    frame.width.toLong() * frame.height <= MAX_SOFTWARE_BITMAP_BYTES / BYTES_PER_PIXEL,
            ) { "Software video frame exceeds the bitmap safety limit" }
            require(frame.width > 0 && frame.height > 0 && frame.strideBytes == frame.width * BYTES_PER_PIXEL) {
                "Software video frame stride is unsupported"
            }
            require(frame.data.remaining().toLong() >= frame.strideBytes.toLong() * frame.height) {
                "Software video frame is truncated"
            }
            val requestedBytes =
                frame.width.toLong() * frame.height * BYTES_PER_PIXEL *
                    MAX_IN_FLIGHT_SOFTWARE_FRAMES
            if (requestedBytes != requestedMemoryBytes) {
                if (inFlightFrames.get() != 0) return false
                frames.clear()
                memory?.close()
                // These two in-flight frames cannot be shortened to satisfy a weighted cache
                // share. Account for them before assigning disposable transport/demux caches.
                // This is required-buffer accounting, not a hard limit on total process memory.
                memory = AndroidPlaybackMemoryBudget.reserve().also { it.resize(requestedBytes) }
                requestedMemoryBytes = requestedBytes
            }
            AndroidPlaybackMemoryBudget.refreshPressure()
            val lease = frames.acquire(frame.width to frame.height) ?: return false
            try {
                // Copy directly from FFmpeg into the leased Bitmap. No intermediate per-frame
                // ByteArray or second pixel copy is needed; FFmpeg may reuse its buffer on return.
                lease.value.copyPixelsFromBuffer(frame.data.duplicate())
            } catch (throwable: Throwable) {
                lease.close()
                throw throwable
            }
            val generation = renderGeneration.get()
            inFlightFrames.incrementAndGet()
            try {
                owner().execute {
                    try {
                        if (generation == renderGeneration.get()) {
                            renderCopiedFrame(output, shape, generation, lease.value, frame.redBlueSwapped)
                            synchronized(lifecycleLock) {
                                if (generation == renderGeneration.get()) {
                                    lastPresentationTimeUs.set(frame.presentationTimeUs)
                                    lastRenderedRealtimeNs.set(System.nanoTime())
                                    renderedFrames.incrementAndGet()
                                }
                            }
                        }
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable
                        if (generation == renderGeneration.get()) failure.compareAndSet(null, throwable)
                    } finally {
                        lease.close()
                        inFlightFrames.decrementAndGet()
                    }
                }
            } catch (throwable: Throwable) {
                lease.close()
                inFlightFrames.decrementAndGet()
                throw throwable
            }
            true
        }

    fun snapshot(): YSoftwareRenderSnapshot =
        YSoftwareRenderSnapshot(
            renderedFrameCount = renderedFrames.get(),
            presentationTimeUs = lastPresentationTimeUs.get(),
            renderedRealtimeNs = lastRenderedRealtimeNs.get(),
            idle = inFlightFrames.get() == 0,
        )

    fun throwIfFailed() {
        failure.get()?.let { throw IllegalStateException("Software Surface rendering failed", it) }
    }

    /** A stuck Surface poisons this lane; recovery must never reuse its executor or leased bitmaps. */
    fun flush() {
        val active =
            synchronized(lifecycleLock) {
                renderGeneration.incrementAndGet()
                executor
            }
        if (active != null && terminalFailure.get() == null) {
            awaitRenderFence(active, RENDER_SHUTDOWN_TIMEOUT_MS)?.let { error ->
                terminalFailure.compareAndSet(null, error)
                failure.compareAndSet(null, error)
            }
        }
        renderedFrames.set(0)
        lastPresentationTimeUs.set(0L)
        lastRenderedRealtimeNs.set(0L)
    }

    fun release() {
        flush()
        surface = null
        val active = synchronized(lifecycleLock) { executor.also { executor = null } }
        val retainedMemory = memory
        memory = null
        // clear retires busy leases, but recycles them only when their Canvas call has returned.
        frames.clear()
        if (active != null) active.execute { retainedMemory?.close() } else retainedMemory?.close()
        active?.shutdown()
        renderGeneration.incrementAndGet()
        renderedFrames.set(0)
        lastPresentationTimeUs.set(0L)
        lastRenderedRealtimeNs.set(0L)
        if (terminalFailure.get() == null) failure.set(null)
        requestedMemoryBytes = 0L
    }

    private fun owner(): ExecutorService =
        synchronized(lifecycleLock) {
            executor ?: Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(
                        {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                            runnable.run()
                        },
                        SOFTWARE_RENDER_THREAD_NAME,
                    ).apply { isDaemon = true }
                }.also { executor = it }
        }

    private fun renderCopiedFrame(
        output: Surface,
        shape: YVideoGeometry,
        generation: Int,
        target: Bitmap,
        redBlueSwapped: Boolean,
    ) {
        require(output.isValid)
        val width = target.width
        val height = target.height
        val canvas =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching(output::lockHardwareCanvas).getOrElse { output.lockCanvas(null) }
            } else {
                output.lockCanvas(null)
            }
        try {
            if (generation != renderGeneration.get()) return
            require(canvas.width > 0 && canvas.height > 0) { "Software video output has no drawable area" }
            canvas.drawColor(Color.BLACK)
            // MediaCodec turns and the GPU renderer squares and turns pictures for the other routes;
            // here the canvas does both, about the centre so a turned picture stays in place.
            val (drawWidth, drawHeight) = softwareFrameDrawSize(width, height, shape, canvas.width, canvas.height)
            canvas.save()
            canvas.translate(canvas.width / 2f, canvas.height / 2f)
            if (shape.drawnRotationDegrees != 0) canvas.rotate(shape.drawnRotationDegrees.toFloat())
            canvas.drawBitmap(
                target,
                null,
                RectF(-drawWidth / 2f, -drawHeight / 2f, drawWidth / 2f, drawHeight / 2f),
                if (redBlueSwapped) redBlueSwappedPaint else paint,
            )
            canvas.restore()
        } finally {
            output.unlockCanvasAndPost(canvas)
        }
    }
}

private const val MAX_SOFTWARE_BITMAP_BYTES = 128L * 1024L * 1024L

private const val SOFTWARE_RENDER_THREAD_NAME = "YCore-Software-Render"
private const val MAX_IN_FLIGHT_SOFTWARE_FRAMES = 2
private const val RENDER_SHUTDOWN_TIMEOUT_MS = 2_000L
private const val BYTES_PER_PIXEL = 4

/** A 4x5 colour matrix, row-major: rows produce R, G, B, A from columns R, G, B, A, offset. */
private fun redBlueSwapMatrix(): ColorMatrix =
    ColorMatrix(
        FloatArray(20).apply {
            this[2] = 1f // red from blue
            this[6] = 1f // green from green
            this[10] = 1f // blue from red
            this[18] = 1f // alpha from alpha
        },
    )
