package com.yfuse.feature.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import android.graphics.Color as AndroidColor

@OptIn(UnstableApi::class)
@Composable
internal fun ExoSurface(
    engine: ExoVideoEngine,
    scaleMode: VideoScaleMode,
    subtitleScale: Float,
    subtitleBrightness: Float,
    subtitlePosition: Float,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                // Deliberately not keepScreenOn: a view that holds the screen awake on its
                // own outranks the window, and PlayerActivity's policy needs to be able to
                // let it time out while paused. See PlayerActivity.applyScreenOnPolicy.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { view ->
            if (view.player !== engine.player) view.player = engine.player
            view.subtitleView?.apply {
                setFractionalTextSize(0.0533f * subtitleScale.coerceIn(0.6f, 1.8f))
                setBottomPaddingFraction((1f - subtitlePosition.coerceIn(0.60f, 0.96f)).coerceIn(0.04f, 0.40f))
                val channel = subtitleBrightnessByte(subtitleBrightness)
                val preserveAuthoredStyle = subtitleBrightness >= 0.999f
                setApplyEmbeddedStyles(preserveAuthoredStyle)
                setApplyEmbeddedFontSizes(preserveAuthoredStyle)
                setStyle(
                    CaptionStyleCompat(
                        AndroidColor.rgb(channel, channel, channel),
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        AndroidColor.BLACK,
                        null,
                    ),
                )
            }
            view.resizeMode =
                when (scaleMode) {
                    VideoScaleMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoScaleMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    VideoScaleMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
        },
        modifier = modifier,
    )
}
