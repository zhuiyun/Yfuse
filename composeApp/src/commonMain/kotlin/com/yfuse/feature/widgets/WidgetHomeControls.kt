package com.yfuse.feature.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.controlSurface
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.readableSurfaceInk
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.designsystem.ThemeText as Text

/** Inline recovery uses the same 48 dp touch target and light styling as the section arrow. */
@Composable
internal fun WidgetRetryButton(
    onRetry: () -> Unit,
    busy: Boolean = false,
) {
    WidgetHomeAction(
        AppIcons.Refresh,
        if (busy) "正在重试" else "重试加载",
        onRetry,
        label = if (busy) "重试中" else "重试",
        busy = busy,
    )
}

/** Header actions stay visually light while retaining a complete, labelled touch target. */
@Composable
internal fun WidgetHomeAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    label: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val ink = readableSurfaceInk(accent, palette.background)
    val rotation =
        if (busy && !LocalAccessibilityOptions.current.reduceMotion && LocalRouteVisible.current) {
            val transition = rememberInfiniteTransition(label = "widgetRefresh")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                label = "widgetRefreshAngle",
            )
        } else {
            rememberUpdatedState(0f)
        }
    Box(
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .alpha(if (enabled || busy) 1f else .5f)
            .pressable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (busy) stateDescription = "正在加载"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = if (label == null) 12.dp else 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeIcon(
                icon,
                null,
                tint = ink,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation.value },
            )
            label?.let { Text(it, color = ink, style = AppTypography.caption.medium, maxLines = 1) }
        }
    }
}

@Composable
internal fun WidgetSectionLoading(
    style: WidgetStyle,
    compact: Boolean,
) {
    val palette = LocalPalette.current
    val poster =
        style in
            setOf(
                WidgetStyle.Poster,
                WidgetStyle.GridTwo,
                WidgetStyle.GridThree,
                WidgetStyle.CenterPoster,
                WidgetStyle.CaptionPoster,
            )
    val width =
        if (poster) {
            if (compact) 108.dp else 136.dp
        } else {
            if (compact) 200.dp else 244.dp
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正在加载内容…", color = palette.sub, style = AppTypography.caption.medium)
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false,
        ) {
            items(3) {
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(
                                if (poster) width / .667f else width / 1.78f,
                            ).clip(AppShapes.thumb)
                            .background(palette.controlSurface),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(.7f)
                            .height(12.dp)
                            .clip(AppShapes.chip)
                            .background(palette.controlSurface),
                    )
                }
            }
        }
    }
}
