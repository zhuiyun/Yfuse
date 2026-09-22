package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.yfuse.core.designsystem.ThemeText as Text

internal const val RESUME_NOTICE_MS = 3_000L

internal class ResumeNoticeState(
    val initialIndex: Int,
    val eligible: Boolean,
) {
    var dismissed by mutableStateOf(false)
        private set
    private var shown by mutableStateOf(false)
    val visible: Boolean get() = eligible && shown && !dismissed

    fun show() {
        if (!dismissed) shown = true
    }

    fun dismiss() {
        dismissed = true
    }
}

@Composable
internal fun rememberResumeNotice(
    resumedFromMs: Long?,
    itemIndex: Int,
    ready: Boolean,
    controlsVisible: Boolean,
    interrupted: Boolean,
): ResumeNoticeState {
    val notice = remember { ResumeNoticeState(itemIndex, resumedFromMs != null && resumedFromMs > 0L) }
    val latestReady by rememberUpdatedState(ready)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice, itemIndex, controlsVisible, interrupted) {
        if (itemIndex != notice.initialIndex || !controlsVisible || interrupted) notice.dismiss()
    }
    LaunchedEffect(notice) {
        if (!notice.eligible) return@LaunchedEffect
        // Initial buffering consumes no reading time. Later buffer ticks cannot restart this timer.
        snapshotFlow { latestReady }.first { it }
        if (notice.dismissed) return@LaunchedEffect
        notice.show()
        val timeout =
            accessibility?.calculateRecommendedTimeoutMillis(
                RESUME_NOTICE_MS,
                containsIcons = false,
                containsText = true,
                containsControls = true,
            ) ?: RESUME_NOTICE_MS
        if (timeout != Long.MAX_VALUE) {
            delay(timeout)
            notice.dismiss()
        }
    }
    return notice
}

/** Quiet edge action, with a full touch target but no bright button or opaque card over the film. */
@Composable
internal fun PlayerResumeNotice(
    notice: ResumeNoticeState,
    onRestart: () -> Unit,
    modifier: Modifier,
) {
    val caption = AppTypography.caption.medium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 4f))
    ChromeVisibility(notice.visible, modifier = modifier, edge = ChromeEdge.Bottom) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("已续播", style = caption, color = Color.White.copy(alpha = 0.55f))
            Text("·", style = caption, color = Color.White.copy(alpha = 0.4f))
            Text(
                "从头播放",
                style = caption,
                color = Color.White.copy(alpha = 0.82f),
                modifier =
                    Modifier
                        .pressable(onClickLabel = "从头播放", onClick = onRestart)
                        .touchTarget()
                        .padding(horizontal = 6.dp),
            )
        }
    }
}
