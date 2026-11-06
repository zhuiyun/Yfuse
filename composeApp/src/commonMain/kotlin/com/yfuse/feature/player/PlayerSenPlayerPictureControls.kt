package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.yfuse.core.data.SenPlayerEnhancementLevel
import com.yfuse.core.data.SenPlayerPicturePreferences
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun PlayerSenPlayerPictureControls(
    preferences: SenPlayerPicturePreferences,
    applied: SenPlayerPictureState?,
) {
    val requested by preferences.state.collectAsState()
    GroupLabel("SenPlayer 画质增强")
    PictureHint(
        if (applied == null) {
            "仅 SenPlayer 内核生效；切换到 SenPlayer 后应用已保存的设置"
        } else {
            "播放中可调整；实际画质取决于片源与设备"
        },
    )
    EnhancementChoices(
        title = "AI 超清",
        requested = requested.upscaling,
        applied = applied?.upscaling,
        onSelect = preferences::setUpscaling,
    )
    PictureHint("适用于 1080p 及以下片源；提高等级会增加耗电和发热")
    EnhancementChoices(
        title = "SDR 转 HDR",
        requested = requested.sdrToHdr,
        applied = applied?.sdrToHdr,
        onSelect = preferences::setSdrToHdr,
    )
    PictureHint("需要支持 HDR10 的屏幕；原版建议峰值亮度超过 1000 nit")
}

@Composable
private fun EnhancementChoices(
    title: String,
    requested: SenPlayerEnhancementLevel,
    applied: SenPlayerEnhancementState?,
    onSelect: (SenPlayerEnhancementLevel) -> Unit,
) {
    GroupLabel(title)
    SenPlayerEnhancementLevel.entries.forEach { level ->
        OptionRow(
            label = level.label,
            selected = level == requested,
            onClick = { onSelect(level) },
            selectionKey = "$title-${level.name}",
        )
    }
    if (applied != null) PictureHint(senPlayerEnhancementStatusLabel(requested, applied))
}

internal fun senPlayerEnhancementStatusLabel(
    requested: SenPlayerEnhancementLevel,
    applied: SenPlayerEnhancementState,
): String =
    when {
        requested != applied.level -> "正在应用 ${requested.label}"
        applied.status == SenPlayerEnhancementStatus.Waiting -> "等待内核应用"
        applied.status == SenPlayerEnhancementStatus.Accepted ->
            when {
                applied.active == null -> "内核已接受，等待状态"
                applied.level == SenPlayerEnhancementLevel.Off -> if (applied.active) "关闭中" else "已关闭"
                applied.active -> "已生效 · ${applied.level.label}"
                else -> "已设置，当前未生效"
            }
        applied.status == SenPlayerEnhancementStatus.Rejected ->
            applied.detail?.takeIf { it.isNotBlank() } ?: "内核未接受此设置"
        else -> applied.detail?.takeIf { it.isNotBlank() } ?: "应用失败，未确认此设置生效"
    }

@Composable
private fun PictureHint(text: String) {
    Text(
        text,
        style = AppTypography.caption.regular,
        color = Color.White.copy(alpha = 0.6f),
    )
}
