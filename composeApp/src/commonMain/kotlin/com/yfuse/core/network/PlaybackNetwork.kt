package com.yfuse.core.network

import com.yfuse.core.data.PlaybackNetworkClass

/** Best-effort current connection class; Unknown is preferred to inventing a constraint. */
expect fun currentPlaybackNetworkClass(): PlaybackNetworkClass
