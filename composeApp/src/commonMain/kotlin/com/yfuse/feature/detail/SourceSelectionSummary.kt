package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun SourceSelectionSummary(
    presentation: SourceSelectionPresentation,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(presentation.selectedLabel, style = AppTypography.caption.strong, color = palette.text)
        Text(
            listOf(
                presentation.recommendationLabel,
                presentation.reason,
            ).filter(String::isNotBlank).joinToString(" · "),
            style = AppTypography.caption.regular,
            color = palette.sub,
        )
        Text("推荐供手动比较；当前来源可用时会保留选择。响应时间不代表下载带宽或设备解码能力。", style = AppTypography.caption.regular, color = palette.sub2)
    }
}
