package com.yfuse.core2.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Process
import android.view.Surface
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

/** Dedicated, bounded Canvas presentation lane for BGRA frames produced by FFmpeg software decode. */
internal class AndroidSoftwareVideoRenderNode {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lifecycleLock = Any()
    private var executor: ExecutorService? = null
    private var bitmap: Bitmap? = null

    @Volatile
    private var surface: Surface? = null

    private val inFlightFrames = AtomicInteger()
    private val renderedFrames = AtomicInteger()
    private val renderGeneration = AtomicInteger()
    private val lastPresentationTimeUs = AtomicLong(0L)
    private val lastRenderedRealtimeNs = AtomicLong(0L)
    private val failure = AtomicReference<Throwable?>(null)

    fun attach(surface: Surface) {
        require(surface.isValid) { "Software video output Surface is invalid" }
        flush()
        this.surface = surface
        failure.set(null)
    }

    /**
     * Copies and queues one frame without waiting for Canvas lock, scale or present.
     *
     * At most two frames (one rendering and one queued) are retained. Returning false asks the
     * decoder lane to keep its reusable FFmpeg buffer until render capacity is available.
     */
    fun tryRender(frame: YSoftwareVideoDecodeResult.Frame): Boolean {
        throwIfFailed()
        requireNotNull(surface).also { require(it.isValid) }
        if (!reserveFrameSlot()) return false
        val copied =
            try {
                val source = frame.data.duplicate()
                ByteArray(source.remaining()).also(source::get)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                inFlightFrames.decrementAndGet()
                throw throwable
            }
        val generation = renderGeneration.get()
        try {
            owner().execute {
                try {
                    if (generation == renderGeneration.get()) {
                        renderCopiedFrame(
                            data = copied,
                            presentationTimeUs = frame.presentationTimeUs,
                            width = frame.width,
                            height = frame.height,
                            strideBytes = frame.strideBytes,
                        )
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    failure.compareAndSet(null, throwable)
                } finally {
                    inFlightFrames.decrementAndGet()
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            inFlightFrames.decrementAndGet()
            throw throwable
        }
        return true
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

    /** Discards queued frames and waits for any current Canvas post before a seek or Surface swap. */
    fun flush() {
        renderGeneration.incrementAndGet()
        val active = synchronized(lifecycleLock) { executor }
        if (active != null) {
            try {
                active.submit { Unit }.get()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                failure.compareAndSet(null, throwable.cause ?: throwable)
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
        active?.shutdown()
        runCatching { active?.awaitTermination(RENDER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        inFlightFrames.set(0)
        renderGeneration.incrementAndGet()
        renderedFrames.set(0)
        lastPresentationTimeUs.set(0L)
        lastRenderedRealtimeNs.set(0L)
        failure.set(null)
        bitmap?.recycle()
        bitmap = null
    }

    private fun reserveFrameSlot(): Boolean {
        while (true) {
            val current = inFlightFrames.get()
            if (current >= MAX_IN_FLIGHT_SOFTWARE_FRAMES) return false
            if (inFlightFrames.compareAndSet(current, current + 1)) return true
        }
    }

    private fun owner(): ExecutorService =
        synchronized(lifecycleLock) {
            executor ?: Executors.newSingleThreadExecutor { runnable ->
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
        data: ByteArray,
        presentationTimeUs: Long,
        width: Int,
        height: Int,
        strideBytes: Int,
    ) {
        val output = requireNotNull(surface).also { require(it.isValid) }
        require(strideBytes == width * BGRA_BYTES_PER_PIXEL) {
            "Software video frame stride is unsupported"
        }
        require(data.size >= strideBytes * height) { "Software video frame is truncated" }
        val target =
            bitmap
                ?.takeIf { it.width == width && it.height == height && !it.isRecycled }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    bitmap?.recycle()
                    bitmap = it
                }
        target.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        val canvas =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching(output::lockHardwareCanvas).getOrElse { output.lockCanvas(null) }
            } else {
                output.lockCanvas(null)
            }
        try {
            require(canvas.width > 0 && canvas.height > 0) { "Software video output has no drawable area" }
            canvas.drawColor(Color.BLACK)
            val sourceAspect = width.toFloat() / height.toFloat()
            val outputAspect = canvas.width.toFloat() / canvas.height.toFloat()
            val destination =
                if (sourceAspect > outputAspect) {
                    val scaledHeight = canvas.width / sourceAspect
                    val top = (canvas.height - scaledHeight) / 2f
                    RectF(0f, top, canvas.width.toFloat(), top + scaledHeight)
                } else {
                    val scaledWidth = canvas.height * sourceAspect
                    val left = (canvas.width - scaledWidth) / 2f
                    RectF(left, 0f, left + scaledWidth, canvas.height.toFloat())
                }
            canvas.drawBitmap(target, null, destination, paint)
        } finally {
            output.unlockCanvasAndPost(canvas)
        }
        lastPresentationTimeUs.set(presentationTimeUs)
        lastRenderedRealtimeNs.set(System.nanoTime())
        renderedFrames.incrementAndGet()
    }
}

private const val SOFTWARE_RENDER_THREAD_NAME = "YCore-Software-Render"
private const val MAX_IN_FLIGHT_SOFTWARE_FRAMES = 2
private const val RENDER_SHUTDOWN_TIMEOUT_MS = 2_000L
private const val BGRA_BYTES_PER_PIXEL = 4
