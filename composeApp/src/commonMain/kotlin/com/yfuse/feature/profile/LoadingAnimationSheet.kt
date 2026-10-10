package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LoadingAnimation
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun LoadingAnimationSheet(
    selected: LoadingAnimation,
    onSelect: (LoadingAnimation) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            "加载动画",
            "${LoadingAnimation.entries.size} 款多彩样式，左右滑动卡片，点击选择并立即保存",
            onClose = onDismiss,
        )
        if (LocalAccessibilityOptions.current.reduceMotion) {
            Text(
                "已开启“减少动画”，预览和实际加载均显示静态样式。",
                color = LocalPalette.current.sub,
                style = AppTypography.caption.regular,
            )
        }
        Spacer(Modifier.height(12.dp))
        val routeVisible = LocalRouteVisible.current
        AnimationCardList(
            options = LoadingAnimation.entries,
            selected = selected,
            key = { "loading-animation-${it.name}" },
            label = { it.label },
            description = { it.description },
            onSelect = onSelect,
        ) { animation, active ->
            CompositionLocalProvider(LocalRouteVisible provides (routeVisible && active)) {
                OrbProgress(
                    modifier = Modifier.clearAndSetSemantics {},
                    size = 64.dp,
                    animation = animation,
                    contentDescription = null,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${selected.label} · ${selected.description}",
            color = LocalPalette.current.sub,
            style = AppTypography.caption.regular,
        )
        Spacer(Modifier.height(12.dp))
        OverlayButton("完成", onClick = overlayDismiss(onDismiss))
    }
}
