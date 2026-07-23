package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Extracts a representative colour from the image at [url], used to tint the
 * detail screen. Returns [fallback] until (or unless) extraction succeeds.
 */
@Composable
expect fun rememberDominantColor(url: String?, fallback: Color): Color
