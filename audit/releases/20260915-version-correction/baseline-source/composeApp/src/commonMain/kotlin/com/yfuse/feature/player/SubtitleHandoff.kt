package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion

@Composable
internal fun <T> SubtitleHandoff(
    value: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val duration = if (LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current) 0 else Motion.QUICK
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = { (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))).using(null) },
        contentAlignment = Alignment.BottomCenter,
        label = "subtitleLineHandoff",
    ) { content(it) }
}
