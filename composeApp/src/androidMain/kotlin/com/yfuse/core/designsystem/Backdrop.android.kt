package com.yfuse.core.designsystem

import android.os.Build

/**
 * `RenderEffect` — and so Compose's `GraphicsLayer.renderEffect` — arrived in API 31.
 *
 * Below it the property is silently ignored, which would leave the capture paying for a
 * full-screen layer every frame and drawing an unblurred copy of the page under the bar.
 * This app's `minSdk` is 26, so that range is real and has to opt out rather than degrade.
 */
actual val supportsBackdropBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
