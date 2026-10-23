package com.yfuse.feature.widgets

import androidx.compose.runtime.staticCompositionLocalOf

/** Editing previews must never fan out requests to the person's media servers. */
internal val LocalWidgetPreview = staticCompositionLocalOf { false }
