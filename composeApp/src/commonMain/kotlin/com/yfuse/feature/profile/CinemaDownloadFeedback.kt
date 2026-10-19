package com.yfuse.feature.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.CinemaMotion
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.cinemaChangeProgress
import com.yfuse.core.designsystem.cinemaMotion
import com.yfuse.core.designsystem.drawCinemaCheck
import com.yfuse.core.offline.DownloadStatus

@Composable
internal fun CinemaDownloadMark(
    status: DownloadStatus,
    color: Color,
) {
    val progress = cinemaChangeProgress(status)
    if (!cinemaMotion()) return
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (
                (
                    fadeIn(tween(CinemaMotion.STATUS, easing = Motion.Curve)) +
                        scaleIn(tween(CinemaMotion.STATUS, easing = Motion.Curve), initialScale = 0.7f)
                ) togetherWith
                    (
                        fadeOut(tween(CinemaMotion.ITEM_EXIT, easing = Motion.Curve)) +
                            scaleOut(tween(CinemaMotion.ITEM_EXIT, easing = Motion.Curve), targetScale = 0.7f)
                    )
            ).using(null)
        },
        label = "cinema-download-status",
    ) { shown ->
        Canvas(Modifier.padding(end = 6.dp).size(16.dp)) {
            when (shown) {
                DownloadStatus.Completed ->
                    drawCinemaCheck(
                        if (status ==
                            shown
                        ) {
                            progress.value
                        } else {
                            1f
                        },
                        Semantic.Success,
                    )
                DownloadStatus.Downloading ->
                    repeat(2) { index ->
                        val x = size.width * (0.35f + index * 0.3f)
                        drawLine(
                            color,
                            Offset(x, size.height * 0.2f),
                            Offset(x, size.height * 0.8f),
                            2.dp.toPx(),
                            StrokeCap.Round,
                        )
                    }
                DownloadStatus.Failed -> {
                    drawArc(
                        color,
                        45f,
                        280f,
                        false,
                        style =
                            androidx.compose.ui.graphics.drawscope
                                .Stroke(1.5.dp.toPx()),
                    )
                    drawLine(
                        color,
                        Offset(size.width * 0.85f, 0f),
                        Offset(size.width, size.height * 0.3f),
                        1.5.dp.toPx(),
                        StrokeCap.Round,
                    )
                }
                else ->
                    drawPath(
                        Path().apply {
                            moveTo(size.width * 0.28f, size.height * 0.18f)
                            lineTo(size.width * 0.82f, size.height * 0.5f)
                            lineTo(size.width * 0.28f, size.height * 0.82f)
                            close()
                        },
                        color,
                    )
            }
        }
    }
}
