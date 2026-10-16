package com.yfuse.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** Compact shortcuts leave the history cards as this tab's primary content. */
@Composable
internal fun WatchingEntrySection(
    onOpenDiscovery: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WatchingEntry("发现", "推荐与热门", AppIcons.Search, onOpenDiscovery, Modifier.weight(1f))
        WatchingEntry("追剧日历", "播出与追更", AppIcons.WatchCalendar, onOpenCalendar, Modifier.weight(1f))
    }
}

@Composable
private fun WatchingEntry(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    Column(
        modifier
            .flatGlass(AppShapes.card, palette.card2, palette.border)
            .pressable(onClickLabel = "打开$title", focusShape = AppShapes.card, onClick = onClick)
            .heightIn(min = 104.dp)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = LocalAccentColors.current.accent, modifier = Modifier.size(22.dp))
        Text(
            title,
            style = AppTypography.caption.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            summary,
            style = AppTypography.caption.regular,
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
