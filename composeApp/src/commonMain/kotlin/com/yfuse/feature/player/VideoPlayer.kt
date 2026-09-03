package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import com.arkivanov.mvikotlin.core.store.Store

/**
 * Hands [items] to the platform's fullscreen player.
 *
 * On Android this starts a dedicated landscape player activity; [onLaunched]
 * then pops this destination so returning lands back on the detail page.
 */
@Composable
expect fun PendingPlayerLauncher(
    store: Store<PlayerIntent, PlayerState, Nothing>,
    startPlaybackRequested: Boolean = true,
    onStoreTransferred: () -> Unit,
    onLaunched: () -> Unit,
)

/** Launches an already-resolved local or synthetic queue. */
@Composable
expect fun PlayerLauncher(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean = true,
    onLaunched: () -> Unit,
)
