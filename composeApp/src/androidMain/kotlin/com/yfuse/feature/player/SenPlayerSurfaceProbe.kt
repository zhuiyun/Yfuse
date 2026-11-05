package com.yfuse.feature.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class SenPlayerSurfaceSample(
    val token: SenPlayerOutputToken,
    val result: Int,
    val lumaRange: Int = 0,
    val changingPicture: Boolean = false,
)

/** Small, bounded, in-memory probes. Never retain/export images or recycle an in-flight bitmap. */
internal class SenPlayerSurfaceProbe {
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean()
    private val active = AtomicReference<SenPlayerOutputToken?>()
    private val evidence = SenPlayerPixelEvidence() // Main-thread confined.

    @Volatile private var latest: SenPlayerSurfaceSample? = null
    private var attempts = 0
    private var nextAt = 0L

    fun invalidate() {
        active.set(null)
    }

    fun result(token: SenPlayerOutputToken): SenPlayerSurfaceSample? = latest?.takeIf { it.token == token }

    // Called by the serialized engine worker. Only the callback touches pixels/evidence.
    fun request(
        view: SurfaceView,
        token: SenPlayerOutputToken,
        stillCurrent: () -> Boolean,
    ) {
        if (active.getAndSet(token) != token) {
            attempts = 0
            nextAt = 0
        }
        val now = SystemClock.elapsedRealtime()
        if (attempts >= 20 || now < nextAt || !busy.compareAndSet(false, true)) return
        attempts++
        nextAt = now + 750
        if (!main.post {
                if (active.get() != token || !stillCurrent() || !view.holder.surface.isValid) {
                    busy.set(false)
                    return@post
                }
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(view, bitmap, { code ->
                        try {
                            if (active.get() == token && stillCurrent()) record(token, code, bitmap)
                        } finally {
                            bitmap.recycle()
                            busy.set(false)
                        }
                    }, main)
                } catch (_: IllegalArgumentException) {
                    if (active.get() == token && stillCurrent()) {
                        record(token, PixelCopy.ERROR_SOURCE_INVALID, bitmap)
                    }
                    bitmap.recycle()
                    busy.set(false)
                }
            }
        ) {
            busy.set(false)
        }
    }

    private fun record(
        token: SenPlayerOutputToken,
        code: Int,
        bitmap: Bitmap,
    ) {
        var low = 255
        var high = 0
        var signature = 1L
        if (code == PixelCopy.SUCCESS) {
            val pixels = IntArray(16 * 16)
            bitmap.getPixels(pixels, 0, 16, 0, 0, 16, 16)
            for (pixel in pixels) {
                val luma = ((pixel shr 16 and 255) * 3 + (pixel shr 8 and 255) * 6 + (pixel and 255)) / 10
                low = minOf(low, luma)
                high = maxOf(high, luma)
                signature = signature * 31 + pixel
            }
        }
        val range = (high - low).coerceAtLeast(0)
        val changing = evidence.observe(token, signature.takeIf { code == PixelCopy.SUCCESS }, range)
        latest = SenPlayerSurfaceSample(token, code, range, changing)
    }
}
