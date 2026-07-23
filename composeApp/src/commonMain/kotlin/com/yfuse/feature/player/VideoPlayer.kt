package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform video surface. Android uses Media3/ExoPlayer; other targets can
 * supply their own implementation later (AVPlayer on iOS, VLC on desktop).
 */
@Composable
expect fun VideoPlayer(url: String, startPositionMs: Long, modifier: Modifier)
