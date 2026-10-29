package com.yfuse.core.network

import com.yfuse.core.data.PlaybackNetworkClass
import kotlinx.coroutines.flow.Flow

/** Best-effort current connection class; Unknown is preferred to inventing a constraint. */
expect fun currentPlaybackNetworkClass(): PlaybackNetworkClass

/**
 * Default-network changes, for screens that rank or route on the connection class.
 *
 * Reading [currentPlaybackNetworkClass] costs binder round-trips, so screens collect this instead
 * of asking again on every recomposition.
 */
expect fun playbackNetworkClasses(): Flow<PlaybackNetworkClass>
