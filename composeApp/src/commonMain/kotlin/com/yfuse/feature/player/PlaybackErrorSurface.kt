package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassInk
import com.yfuse.core.designsystem.InlineLoadingContent
import com.yfuse.core.designsystem.LocalGlassMaterials
import com.yfuse.core.designsystem.LocalMotionBudget
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.mutedGlassPanel
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberTouchTargetFocus
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.designsystem.ThemeText as Text

internal fun playbackFailureHeadline(
    kind: PlaybackFailureKind?,
    defaultTitle: String = "播放遇到问题",
): String =
    when (kind) {
        PlaybackFailureKind.Authorization -> "服务器登录已失效"
        PlaybackFailureKind.Drm -> "内容许可证受限"
        PlaybackFailureKind.Network -> "网络连接异常"
        PlaybackFailureKind.Container -> "媒体文件解析失败"
        PlaybackFailureKind.Decoder -> "音视频解码失败"
        PlaybackFailureKind.Renderer -> "画面输出异常"
        PlaybackFailureKind.AudioSink -> "声音输出异常"
        else -> defaultTitle
    }

/**
 * Redesigned unified liquid-glass failure surface shared by in-player failures and pre-launch
 * preparation errors. Blocks stray touches from leaking to underlying player gestures, wraps
 * cleanly on narrow/large-font windows, auto-focuses the primary action for remotes/keyboards,
 * and omits engine-switching controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackErrorSurface(
    title: String,
    message: String,
    stage: String? = null,
    advice: String? = null,
    preferBackAsPrimary: Boolean = false,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onExternalPlayer: (() -> Unit)? = null,
    alternatives: List<Pair<String, () -> Unit>> = emptyList(),
    onExplain: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    scrimAlpha: Float = 0.74f,
) {
    val primaryFocus = remember { FocusRequester() }
    var retrying by remember(message) { mutableStateOf(false) }
    LaunchedEffect(primaryFocus, message) {
        withFrameNanos { }
        runCatching { primaryFocus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            // Consume taps on the backdrop so they cannot leak into underlying player gestures.
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .shadow(Shadows.playerSheet, AppShapes.sheet)
                    .mutedGlassPanel(
                        shape = AppShapes.sheet,
                        samplePage = false,
                        dark = true,
                        materialOverride =
                            LocalGlassMaterials.current.dark.copy(
                                tintRgb = 0x191E27,
                                opacity = 0.84f,
                                rim = 0.46f,
                                rimWidth = 0.75f,
                                ink = GlassInk.Light,
                            ),
                        translucentOverVideo = !LocalMotionBudget.current.reduced,
                    ).padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!stage.isNullOrBlank()) {
                Text(
                    text = stage,
                    style = AppTypography.caption.strong,
                    color = DarkPalette.error,
                    modifier =
                        Modifier
                            .background(DarkPalette.error.copy(alpha = 0.14f), CircleShape)
                            .border(0.75.dp, DarkPalette.error.copy(alpha = 0.34f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Column(
                Modifier.liveStatus(assertive = true),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = AppTypography.section.strong,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = message,
                    style = AppTypography.body.regular,
                    color = Color.White.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!advice.isNullOrBlank() && advice != message) {
                    Text(
                        text = advice,
                        style = AppTypography.caption.regular,
                        color = Color.White.copy(alpha = 0.64f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaybackErrorActionPill(
                    label = "返回",
                    primary = preferBackAsPrimary,
                    modifier = if (preferBackAsPrimary) Modifier.focusRequester(primaryFocus) else Modifier,
                    onClick = onBack,
                )
                PlaybackErrorActionPill(
                    label = if (retrying) "正在重试…" else "重试",
                    primary = !preferBackAsPrimary,
                    loading = retrying,
                    enabled = !retrying,
                    modifier = if (!preferBackAsPrimary) Modifier.focusRequester(primaryFocus) else Modifier,
                    onClick = {
                        if (!retrying) {
                            retrying = true
                            onRetry()
                        }
                    },
                )
                onExternalPlayer?.let { open ->
                    PlaybackErrorActionPill(
                        label = "外部播放器",
                        primary = false,
                        onClick = open,
                    )
                }
            }

            if (alternatives.isNotEmpty()) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )
                Text(
                    text = "其他可用方式",
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.62f),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    alternatives.forEach { (label, action) ->
                        val chipFocus = rememberTouchTargetFocus(AppShapes.pill)
                        Box(
                            Modifier
                                .pressable(
                                    focusShape = chipFocus,
                                    onClickLabel = label,
                                    onClick = action,
                                ).touchTarget(focus = chipFocus)
                                .glass(
                                    shape = AppShapes.pill,
                                    fill = Color.White.copy(alpha = 0.10f),
                                    border = Color.White.copy(alpha = 0.24f),
                                ).padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = AppTypography.caption.strong,
                                color = Color.White.copy(alpha = 0.92f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            onExplain?.let { explain ->
                val linkFocus = rememberTouchTargetFocus(AppShapes.pill)
                Box(
                    Modifier
                        .pressable(
                            focusShape = linkFocus,
                            onClickLabel = "查看详细诊断与导出日志",
                            onClick = explain,
                        ).touchTarget(focus = linkFocus)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "查看详细诊断与导出日志",
                        style = AppTypography.caption.medium,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }

            footer?.invoke()
        }
    }
}

@Composable
private fun PlaybackErrorActionPill(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val focus = rememberTouchTargetFocus(AppShapes.pill)
    val ink = if (primary) Color(0xFF141A26) else Color.White.copy(alpha = 0.92f)
    Row(
        modifier =
            modifier
                .pressable(
                    enabled = enabled,
                    focusShape = focus,
                    onClickLabel = label,
                    onClick = onClick,
                ).touchTarget(focus = focus)
                .liquidGlass(
                    shape = AppShapes.pill,
                    fill = if (primary) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.12f),
                    border = if (primary) Color.White else Color.White.copy(alpha = 0.28f),
                    over = Color(0xFF191E27),
                    sheen = if (primary) 0.9f else 0.65f,
                ).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            InlineLoadingContent(loading = true, slotSize = 14.dp, color = ink) {}
        }
        Text(
            text = label,
            style = if (primary) AppTypography.body.strong else AppTypography.body.medium,
            color = ink,
            maxLines = 1,
        )
    }
}
