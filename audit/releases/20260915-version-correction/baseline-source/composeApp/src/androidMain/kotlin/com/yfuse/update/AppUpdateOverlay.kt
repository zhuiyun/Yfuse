package com.yfuse.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.rememberDecorativePhase
import com.yfuse.core.designsystem.sc

/**
 * 进入首页自动检测更新.
 *
 * This composes only once the launch animation has handed over, so the check runs as the main
 * UI appears and again whenever 首页 is entered. [AppUpdateManager.checkIfDue] throttles the
 * request, and the dialog below it opens automatically at most once a day per version.
 *
 * The download is the one long-running thing this dialog shows, so it carries the same motion
 * as a transfer on the 下载 page: the fill eases to each reported value instead of stepping, a
 * highlight flows along it while bytes are actually moving, it dims when paused, and on
 * completion the track draws in and a check pops where the numbers were. The sections that
 * appear and disappear around it (progress, error, notes) grow into place rather than cutting.
 */
@Composable
fun AppUpdateOverlay(
    manager: AppUpdateManager,
    root: RootComponent,
) {
    val activeTab by root.activeTab.subscribeAsState()
    LaunchedEffect(Unit) { manager.checkOnLaunch() }
    LaunchedEffect(activeTab) {
        if (activeTab == RootComponent.Tab.Home) manager.checkIfDue()
    }

    val visible by manager.promptVisible.collectAsState()
    if (!visible) return
    val state by manager.state.collectAsState()
    val manifest =
        when (val value = state) {
            is UpdateState.Available -> value.manifest
            is UpdateState.Downloading -> value.manifest
            is UpdateState.Paused -> value.manifest
            is UpdateState.Ready -> value.manifest
            is UpdateState.Error -> value.manifest
            else -> null
        } ?: return

    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val downloading = state as? UpdateState.Downloading
    val paused = state as? UpdateState.Paused
    val ready = state is UpdateState.Ready
    val transferring = downloading != null || paused != null
    val sectionMs = if (reduceMotion) 0 else Motion.DISCLOSURE
    val swapMs = if (reduceMotion) 0 else Motion.QUICK
    GlassDialog(onDismiss = manager::dismissPrompt) {
        OverlayHeader(
            title = "发现新版本 ${manifest.versionName}",
            subtitle =
                when {
                    ready -> "下载完成，安装后即可使用"
                    downloading != null -> "可以关闭本窗口，下载会在后台继续"
                    else -> "当前版本可直接在应用内升级"
                },
            onClose = manager::dismissPrompt,
        )
        if (manifest.notes.isNotBlank()) {
            Text(
                manifest.notes,
                style = sc(12f, 400, lineHeight = 19f),
                color = palette.body,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        // The transfer block stays composed through 就绪 so the track can finish its own
        // draw-in instead of being removed on the frame the last byte lands.
        AnimatedVisibility(
            visible = transferring || ready,
            enter =
                fadeIn(tween(sectionMs, easing = Motion.Curve)) +
                    expandVertically(tween(sectionMs, easing = Motion.Curve)),
            exit =
                fadeOut(tween(sectionMs, easing = Motion.Curve)) +
                    shrinkVertically(tween(sectionMs, easing = Motion.Curve)),
        ) {
            val progress = downloading?.progress ?: paused?.progress ?: if (ready) 1f else 0f
            val downloaded =
                downloading?.downloadedBytes ?: paused?.downloadedBytes ?: if (ready) manifest.size else 0L
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                UpdateProgressTrack(
                    progress = progress,
                    flowing = downloading != null,
                    complete = ready,
                    trackColor = palette.card2,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Only the phase word crossfades; the numbers under it just tick.
                    AnimatedContent(
                        targetState =
                            when {
                                ready -> "下载完成"
                                downloading != null -> "正在下载"
                                else -> "已暂停"
                            },
                        transitionSpec = {
                            fadeIn(tween(swapMs, easing = Motion.Curve)) togetherWith
                                fadeOut(tween(swapMs, easing = Motion.Curve))
                        },
                        label = "update-phase",
                    ) { phase ->
                        Text(phase, style = mr(10.5f, 500), color = palette.sub2)
                    }
                    AnimatedVisibility(
                        visible = ready,
                        enter = fadeIn(tween(swapMs)),
                        exit = fadeOut(tween(swapMs)),
                    ) {
                        BurstIcon(
                            icon = AppIcons.Check,
                            active = ready,
                            contentDescription = null,
                            tint = Brand.Online,
                            burstColor = Brand.Online,
                            iconSize = 12.dp,
                        )
                    }
                    Text(
                        buildString {
                            append((progress * 100).toInt())
                            append("% · ")
                            append(formatUpdateBytes(downloaded))
                            append(" / ")
                            append(formatUpdateBytes(manifest.size))
                        },
                        style = mr(10.5f, 500).copy(fontFeatureSettings = "tnum"),
                        color = palette.sub2,
                    )
                }
                AnimatedVisibility(
                    visible = paused?.message != null,
                    enter = fadeIn(tween(sectionMs)) + expandVertically(tween(sectionMs, easing = Motion.Curve)),
                    exit = fadeOut(tween(sectionMs)) + shrinkVertically(tween(sectionMs, easing = Motion.Curve)),
                ) {
                    Text(paused?.message.orEmpty(), style = sc(11.5f, 500), color = Brand.Danger)
                }
            }
        }
        val error = state as? UpdateState.Error
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(tween(sectionMs)) + expandVertically(tween(sectionMs, easing = Motion.Curve)),
            exit = fadeOut(tween(sectionMs)) + shrinkVertically(tween(sectionMs, easing = Motion.Curve)),
        ) {
            Text(
                error?.message.orEmpty(),
                style = sc(11.5f, 500),
                color = Brand.Danger,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayButton(
                label = if (downloading != null) "后台下载" else "稍后",
                onClick = manager::dismissPrompt,
                modifier = Modifier.weight(1f),
            )
            val primaryLabel =
                when {
                    downloading != null -> "暂停"
                    paused != null -> "继续下载"
                    ready -> "立即安装"
                    else -> "下载并安装"
                }
            // The key's role changes with the phase; its label crossfades so the button is one
            // object changing its mind, not a different button appearing.
            AnimatedContent(
                targetState = primaryLabel,
                transitionSpec = {
                    fadeIn(tween(swapMs, easing = Motion.Curve)) togetherWith
                        fadeOut(tween(swapMs, easing = Motion.Curve))
                },
                modifier = Modifier.weight(1f),
                label = "update-primary",
            ) { label ->
                OverlayButton(
                    label = label,
                    onClick = {
                        when (val value = state) {
                            is UpdateState.Downloading -> manager.pauseDownload()
                            is UpdateState.Ready -> manager.install(value.apk)
                            else -> manager.download(manifest)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tone = OverlayButtonTone.Primary,
                )
            }
        }
    }
}

/**
 * The 4dp track under the update transfer. The fill eases to each reported value, a highlight
 * flows along it while bytes move, it dims to half while paused, and on completion the whole
 * track draws in from the right over [Motion.DOWNLOAD_COMPLETE] and leaves a single point.
 * All reads happen in the draw node, so progress ticks never recompose the dialog's layout.
 */
@Composable
private fun UpdateProgressTrack(
    progress: Float,
    flowing: Boolean,
    complete: Boolean,
    trackColor: Color,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val fill: State<Float> =
        animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = Motion.settle(reduceMotion),
            label = "updateFill",
        )
    val dim: State<Float> =
        animateFloatAsState(
            targetValue = if (flowing || complete) 1f else 0.55f,
            animationSpec = Motion.settle(reduceMotion),
            label = "updateDim",
        )
    val collapse: State<Float> =
        animateFloatAsState(
            targetValue = if (complete) 1f else 0f,
            animationSpec = tween(if (reduceMotion) 0 else Motion.DOWNLOAD_COMPLETE, easing = Motion.Curve),
            label = "updateComplete",
        )
    val flow =
        rememberDecorativePhase(
            enabled = flowing && !reduceMotion,
            periodMillis = Motion.DOWNLOAD_FLOW,
            label = "updateFlow",
        )
    val accent = Brand.Primary
    Box(
        Modifier.fillMaxWidth().height(4.dp).drawWithCache {
            val radius = CornerRadius(size.height / 2f)
            onDrawBehind {
                val amount = collapse.value.coerceIn(0f, 1f)
                // Draw in toward the left as the package becomes installable; what remains is a dot.
                val trackWidth = size.height + (size.width - size.height) * (1f - amount)
                if (trackWidth <= 0f) return@onDrawBehind
                drawRoundRect(trackColor, Offset.Zero, Size(trackWidth, size.height), radius)
                val fillWidth = (trackWidth * fill.value).coerceAtLeast(if (fill.value > 0f) size.height else 0f)
                if (fillWidth <= 0f) return@onDrawBehind
                clipRect(right = fillWidth) {
                    drawRoundRect(
                        accent.copy(alpha = accent.alpha * dim.value),
                        Offset.Zero,
                        Size(fillWidth, size.height),
                        radius,
                    )
                    if (flowing && amount < 1f) {
                        val band = fillWidth * 0.35f
                        val head = -band + (fillWidth + 2f * band) * flow.value
                        drawRect(
                            brush =
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                                    startX = head - band,
                                    endX = head + band,
                                ),
                            topLeft = Offset.Zero,
                            size = Size(fillWidth, size.height),
                        )
                    }
                }
            }
        },
    )
}

internal fun formatUpdateBytes(bytes: Long): String {
    val megabytes = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return if (megabytes >= 100.0) {
        "${megabytes.toInt()} MB"
    } else {
        "${(megabytes * 10).toInt() / 10.0} MB"
    }
}
