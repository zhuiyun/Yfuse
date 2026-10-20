package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class OperationTone { Working, Success, Error, Info }

/** Only authoritative work states enter here. Animation never drives an operation or its progress. */
@Composable
fun OperationFeedback(
    message: String,
    tone: OperationTone = OperationTone.Info,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val color =
        when (tone) {
            OperationTone.Error -> palette.error
            OperationTone.Success -> palette.success
            else -> LocalAccentColors.current.accent
        }
    Row(
        modifier
            .fillMaxWidth()
            .motionAwareAnimateContentSize()
            .glass(AppShapes.control, color.copy(alpha = 0.08f), color.copy(alpha = 0.2f))
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MotionSwap(tone, Modifier.size(20.dp)) { state ->
            when (state) {
                OperationTone.Working -> OrbProgress(size = 18.dp, color = color, contentDescription = null)
                else ->
                    ThemeText(
                        when (state) {
                            OperationTone.Success -> "✓"
                            OperationTone.Error -> "!"
                            else -> "·"
                        },
                        color = color,
                        style = AppTypography.body.strong,
                    )
            }
        }
        // One text node: changing messages cannot duplicate announcements or live content.
        ThemeText(message, color = color, style = AppTypography.caption.medium, modifier = Modifier.weight(1f))
    }
}
