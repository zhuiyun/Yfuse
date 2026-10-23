package com.yfuse.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
        WatchingEntry("发现", AppIcons.Search, onOpenDiscovery, Modifier.weight(1f))
        WatchingEntry("追剧日历", AppIcons.WatchCalendar, onOpenCalendar, Modifier.weight(1f))
    }
}

@Composable
private fun WatchingEntry(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .flatGlass(AppShapes.control, palette.card2, Color.White.copy(alpha = if (palette.isDark) 0.18f else 0.75f))
            .pressable(role = Role.Button, onClickLabel = "打开$title", focusShape = AppShapes.control, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, tint = LocalAccentColors.current.accent, modifier = Modifier.size(20.dp))
        Text(
            title,
            modifier = Modifier.weight(1f, fill = false),
            style = AppTypography.body.medium,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
