package com.yfuse.feature.player

import androidx.compose.runtime.Composable

/**
 * Hands [items] to the platform's fullscreen player.
 *
 * On Android this starts a dedicated landscape player activity; [onLaunched]
 * then pops this destination so returning lands back on the detail page.
 */
@Composable
expect fun PlayerLauncher(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    onLaunched: () -> Unit,
)
