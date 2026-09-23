package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * "推荐" 之类的一次性状态标签，标出来源/版本列表里被算法选中的那一项。
 *
 * 早先 `detail/DetailFileSections.kt` 与 `detail/SourceListDialog.kt` 各自写了一份
 * `Color(0xFF9A6B12)` 压在 30% 透明的 `#F5C86A` 上；深色主题下估算对比度约 1.8:1，远低于
 * WCAG AA 的 4.5:1。这里改成不透明的 [Semantic.Warning] 底配 [RecommendBadgeInk] 深墨字——不透明
 * 意味着对比度不再随下方卡片/页面底色变化，同一套颜色浅色、深色主题下都成立，估算约 6.2:1。
 */
@Composable
fun RecommendBadge(
    modifier: Modifier = Modifier,
    text: String = "推荐",
) {
    Text(
        text,
        style = AppTypography.caption.strong,
        color = RecommendBadgeInk,
        modifier =
            modifier
                .clip(AppShapes.chip)
                .background(Semantic.Warning)
                .padding(horizontal = Dimens.space.sm, vertical = 2.dp),
    )
}

/** Near-black warm ink — the fixed pairing that keeps [RecommendBadge] AA-compliant on a solid fill. */
internal val RecommendBadgeInk = Color(0xFF2E1500)
