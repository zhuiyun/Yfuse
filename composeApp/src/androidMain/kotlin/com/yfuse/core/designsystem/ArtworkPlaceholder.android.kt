package com.yfuse.core.designsystem

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun blurHashImage(
    pixels: IntArray,
    width: Int,
    height: Int,
): ImageBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
