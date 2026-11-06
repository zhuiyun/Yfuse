package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.render.YFrameRateSwitchMode

/** A bounded, silent NativeDirect preview; unsupported sources report failure to their poster UI. */
fun createMutedYCorePreviewPlayer(
    context: Context,
    uri: String,
): YPlayer =
    AndroidNativeDirectYPlayer(
        context = context.applicationContext,
        request = mutedYCorePreviewRequest(uri),
        audioDisabled = true,
        frameRateSwitchMode = YFrameRateSwitchMode.Disabled,
    )

internal fun mutedYCorePreviewRequest(uri: String): YPlayerOpenRequest =
    YPlayerOpenRequest(
        items =
            listOf(
                YMediaItem(
                    id = "muted-preview",
                    uri = uri,
                    allowNextItemPreparation = false,
                    initialTrackSelection = YInitialTrackSelection(subtitlesDisabled = true),
                ),
            ),
        autoPlay = true,
        autoNext = false,
    )
