package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.lightOnChange
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Where the gesture HUD's centre sits while the paused key or the ending's keys are up: below their
 * discs by half the HUD and a little air, the clearance the scrub preview keeps above the middle.
 */
private val GestureHudBelowCentreKeys = CenterKeySize / 2 + 32.dp

/**
 * The readout the picture's gestures answer with in the middle of the frame: 快进 30 秒, 音量 40%,
 * where a scrub will land. While [centreKeysShown] it sits beneath those keys and leaves out what
 * they already say ([gestureHudLine]).
 *
 * A composable of its own so that only it follows the text. A drag across the picture rewrites
 * the reading on every move; read by PlayerControls, that rebuilt the whole control tree once a
 * frame for as long as the finger moved. [hud] is read here and nowhere above.
 *
 * 静息 keeps the crossfade and drops the pop; 减少动画 swaps the text outright.
 */
@Composable
internal fun PlayerGestureHud(
    hud: () -> String?,
    centreKeysShown: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val calm = calmMotion()
    val hudLine = gestureHudLine(hud(), centreKeysShown)
    // Kept while a line leaves, so it fades out where it was rather than jumping.
    val hudBelowKeys = remember { booleanArrayOf(false) }
    if (hudLine != null) hudBelowKeys[0] = centreKeysShown
    AnimatedContent(
        targetState = hudLine,
        contentKey = ::gestureHudMotionKey,
        transitionSpec = {
            val swap =
                when {
                    reduceMotion -> fadeIn(snap()) togetherWith fadeOut(snap())
                    calm -> fadeIn(Motion.tween(Motion.QUICK)) togetherWith fadeOut(Motion.tween(Motion.QUICK))
                    else ->
                        (
                            fadeIn(Motion.tween(Motion.QUICK)) +
                                scaleIn(Motion.settle(), initialScale = HUD_SCALE_IN)
                        ) togetherWith
                            (
                                fadeOut(Motion.tween(Motion.QUICK)) +
                                    scaleOut(
                                        Motion.tween(Motion.QUICK),
                                        targetScale = HUD_SCALE_OUT,
                                    )
                            )
                }
            swap using Motion.sizeTransform(reduceMotion)
        },
        contentAlignment = Alignment.Center,
        modifier = modifier.offset(y = if (hudBelowKeys[0]) GestureHudBelowCentreKeys else 0.dp),
        label = "gesture-hud",
    ) { value ->
        if (value != null) {
            Text(
                value,
                style = AppTypography.body.strong,
                color = Color.White,
                modifier =
                    Modifier
                        .lightOnChange(
                            value,
                            LightEffect.Trail,
                            emitWhen = value.startsWith("音量 ") || value.startsWith("亮度 "),
                        ).semantics { liveRegion = LiveRegionMode.Polite }
                        .glass(
                            shape = AppShapes.pill,
                            fill = Color.Black.copy(alpha = 0.56f),
                            border = Color.White.copy(alpha = 0.24f),
                        ).padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}
