package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.ParticleLight
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.lightFeedback
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberLightFeedback
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun ParticleLightSheet(
    selected: ParticleLight,
    onSelect: (ParticleLight) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader("粒子光效", "只在操作时出现，播放画面保持安静", onClose = onDismiss)
        ParticleLight.entries.forEach { mode ->
            OverlayOptionRow(
                label = mode.label,
                description =
                    when (mode) {
                        ParticleLight.Off -> "关闭粒子，不改变原来的页面与弹窗动画"
                        ParticleLight.Gentle -> "少量细光粒、短光尾与局部聚光"
                        ParticleLight.Enhanced -> "增加细节，开放海报边缘与局部消散效果"
                    },
                selected = selected == mode,
                onClick = { onSelect(mode) },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (LocalAccessibilityOptions.current.reduceMotion) {
            Text("“减少动画”已开启，粒子与预览均保持关闭。", color = palette.sub, style = AppTypography.caption.regular)
        }
        Spacer(Modifier.height(8.dp))
        Text("点击下面的卡片预览，不会触发播放或更改其他设置。", color = palette.sub, style = AppTypography.caption.regular)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LightEffect.entries.forEach { effect ->
                val light = rememberLightFeedback()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .lightFeedback(light)
                        .pressable(lightFeedback = false, onClick = { light.emit(effect) })
                        .flatGlass(GlassShapes.chip, palette.card2, palette.border)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (effect) {
                            LightEffect.Trail -> "跟手光粒 · 音量与进度"
                            LightEffect.Converge -> "聚拢确认 · 收藏与选择"
                            LightEffect.Edge -> "边缘扫光 · 弹窗与卡片"
                            LightEffect.Dissolve -> "局部消散 · 移除操作"
                            LightEffect.Node -> "节点点亮 · 开关与日期"
                            LightEffect.Dust -> "细微光尘 · 海报边缘"
                        },
                        color = palette.text,
                        style = AppTypography.body.medium,
                    )
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}
