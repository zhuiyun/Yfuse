package com.yfuse.core2.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.Surface

/** GPU-backed Canvas presentation for BGRA frames produced by FFmpeg software decode. */
internal class AndroidSoftwareVideoRenderNode {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var surface: Surface? = null
    private var bitmap: Bitmap? = null

    fun attach(surface: Surface) {
        require(surface.isValid) { "Software video output Surface is invalid" }
        this.surface = surface
    }

    fun render(frame: YSoftwareVideoDecodeResult.Frame) {
        val output = requireNotNull(surface).also { require(it.isValid) }
        require(frame.strideBytes == frame.width * BGRA_BYTES_PER_PIXEL) {
            "Software video frame stride is unsupported"
        }
        val target =
            bitmap
                ?.takeIf { it.width == frame.width && it.height == frame.height && !it.isRecycled }
                ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
                    bitmap?.recycle()
                    bitmap = it
                }
        target.copyPixelsFromBuffer(frame.data.duplicate().apply { position(0) })
        val canvas =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching(output::lockHardwareCanvas).getOrElse { output.lockCanvas(null) }
            } else {
                output.lockCanvas(null)
            }
        try {
            require(canvas.width > 0 && canvas.height > 0) { "Software video output has no drawable area" }
            canvas.drawColor(Color.BLACK)
            val sourceAspect = frame.width.toFloat() / frame.height.toFloat()
            val outputAspect = canvas.width.toFloat() / canvas.height.toFloat()
            val destination =
                if (sourceAspect > outputAspect) {
                    val height = canvas.width / sourceAspect
                    val top = (canvas.height - height) / 2f
                    RectF(0f, top, canvas.width.toFloat(), top + height)
                } else {
                    val width = canvas.height * sourceAspect
                    val left = (canvas.width - width) / 2f
                    RectF(left, 0f, left + width, canvas.height.toFloat())
                }
            canvas.drawBitmap(target, null, destination, paint)
        } finally {
            output.unlockCanvasAndPost(canvas)
        }
    }

    fun release() {
        surface = null
        bitmap?.recycle()
        bitmap = null
    }
}

private const val BGRA_BYTES_PER_PIXEL = 4
