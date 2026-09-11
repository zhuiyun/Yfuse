package com.yfuse.feature.player

import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion

/** Each track owns its measured height, including wrapped lines and bitmap captions. */
@Composable
internal fun BottomSubtitleStack(
    position: Float,
    primary: @Composable () -> Unit,
    /** Null while only one track is running; its presence is what the stack's height follows. */
    secondary: (@Composable () -> Unit)? = null,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 字幕位置 is a slider, and a preset jumps it across a third of the screen; the lines used
        // to teleport to the new height. That is what this animation is for, and it is the only
        // one left here: the stack itself used to animate its own height as well, which meant
        // every line of dialogue that wrapped onto a different number of lines than the last one
        // sprang the whole stack up or down — several times a minute, while it was being read.
        // A line changing is [SubtitleHandoff]'s fade; only the tracks themselves change height.
        val bottomPadding by animateDpAsState(
            targetValue = maxHeight * (1f - position.coerceIn(0.60f, 0.96f)),
            animationSpec = Motion.settle(reduceMotion),
            label = "subtitlePosition",
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            primary()
            secondary?.invoke()
        }
    }
}

@Composable
internal fun BottomSubtitleText(
    text: CharSequence,
    scale: Float,
    appearance: SubtitleAppearance,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // 字幕大小, 字幕亮度 and the colour pickers are all live controls: the old `update` block took
    // whatever the preference said this frame, so dragging the size slider stepped the text through
    // a stack of discrete sizes and the brightness ramp flickered between them. Interpolating here
    // keeps `update` a handful of setters that happen to be handed a moving value.
    val animatedScale by animateFloatAsState(
        targetValue = scale.coerceIn(0.6f, 1.8f),
        animationSpec = Motion.settle(reduceMotion),
        label = "subtitleScale",
    )
    val textColor by animateColorAsState(
        targetValue = Color(appearance.textColorArgb.toInt()),
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "subtitleTextColor",
    )
    val backgroundColor by animateColorAsState(
        targetValue = Color(appearance.backgroundColorArgb.toInt()),
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "subtitleBackgroundColor",
    )
    val outlineColor by animateColorAsState(
        targetValue = Color(appearance.outlineColorArgb.toInt()),
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "subtitleOutlineColor",
    )
    val outlineWidth by animateFloatAsState(
        targetValue = appearance.outlineWidth.coerceIn(0f, 6f),
        animationSpec = Motion.settle(reduceMotion),
        label = "subtitleOutlineWidth",
    )
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
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * animatedScale)
                    view.setTextColor(textColor.toArgb())
                    view.setBackgroundColor(backgroundColor.toArgb())
                    // Zero still has to mean "no outline": a shadow radius of 0 with a colour is a
                    // no-op layer, and the platform treats it as one.
                    view.setShadowLayer(outlineWidth, 0f, 0f, outlineColor.toArgb())
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
