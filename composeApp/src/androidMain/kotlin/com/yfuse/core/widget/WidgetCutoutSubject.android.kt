package com.yfuse.core.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Runs on the cutout pipeline's IO worker; no image decoding is added to composition. */
internal fun readWidgetCutoutSubject(file: File): WidgetCutoutQuality? {
    val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
    var sampled: Bitmap? = null
    return try {
        val scale = minOf(256f / bitmap.width, 256f / bitmap.height, 1f)
        val mask =
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        sampled = mask
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        dominantWidgetCutoutSubject(mask.width, mask.height, pixels)
    } finally {
        if (sampled !== bitmap) sampled?.recycle()
        bitmap.recycle()
    }
}
