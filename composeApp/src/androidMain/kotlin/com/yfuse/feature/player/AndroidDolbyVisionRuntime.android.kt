package com.yfuse.feature.player

import android.content.Context
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackRuntimeEnvironment

/** Device performance is not evidence that this source's Dolby frames were presented. */
@Suppress("UNUSED_PARAMETER")
internal fun dolbyVisionRuntimeCapabilities(
    context: Context,
    environment: PlaybackRuntimeEnvironment,
): PlaybackDolbyVisionRuntimeCapabilities = PlaybackDolbyVisionRuntimeCapabilities()
