package com.yfuse.feature.player

import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/** Each track owns its measured height, including wrapped lines and bitmap captions. */
@Composable
internal fun BottomSubtitleStack(
    position: Float,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = maxHeight * (1f - position.coerceIn(0.60f, 0.96f))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            primary()
            secondary()
        }
    }
}

@Composable
internal fun BottomSubtitleText(
    text: CharSequence,
    scale: Float,
    appearance: SubtitleAppearance,
) {
    SubtitleHandoff(text) { visibleText ->
        if (visibleText.isNotBlank()) {
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                },
                update = { view ->
                    view.text =
                        if (appearance == SubtitleAppearance() &&
                            visibleText is Spanned
                        ) {
                            visibleText
                        } else {
                            visibleText.toString()
                        }
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * scale.coerceIn(0.6f, 1.8f))
                    view.setTextColor(appearance.textColorArgb.toInt())
                    view.setBackgroundColor(appearance.backgroundColorArgb.toInt())
                    view.setShadowLayer(
                        appearance.outlineWidth.coerceIn(0f, 6f),
                        0f,
                        0f,
                        appearance.outlineColorArgb.toInt(),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@UnstableApi
@Composable
internal fun ExoSubtitleBlock(
    cues: List<Cue>,
    scale: Float,
    appearance: SubtitleAppearance,
) {
    // Keep the empty target in the handoff so the previous line can finish its QUICK exit.
    SubtitleHandoff(cues) { visibleCues ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            visibleCues.forEach { cue ->
                cue.bitmap?.let { bitmap ->
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val fraction = cue.size.takeIf { it > 0f && it <= 1f } ?: 0.8f
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.width(maxWidth * (fraction * scale).coerceIn(0.01f, 1f)),
                        )
                    }
                }
                cue.text?.let { BottomSubtitleText(it, scale, appearance) }
            }
        }
    }
}
