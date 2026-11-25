package com.yfuse.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.readableSurfaceInk
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** Clean high-contrast shortcut cards above the playback history timeline. */
@Composable
internal fun WatchingEntrySection(
    onOpenDiscovery: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WatchingEntry(
            title = "发现",
            subtitle = "探索推荐",
            icon = AppIcons.Search,
            tint = SettingTint.servers,
            onClick = onOpenDiscovery,
            modifier = Modifier.weight(1f),
        )
        WatchingEntry(
            title = "追剧日历",
            subtitle = "更新提醒",
            icon = AppIcons.WatchCalendar,
            tint = SettingTint.playback,
            onClick = onOpenCalendar,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WatchingEntry(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val cardSurface = palette.card.compositeOver(palette.background)
    val cardBorder = palette.text.copy(alpha = if (palette.isDark) 0.12f else 0.08f)
    val badgeFill = tint.copy(alpha = if (palette.isDark) 0.22f else 0.13f)
    val badgeResolved = badgeFill.compositeOver(cardSurface)
    val badgeInk = readableSurfaceInk(tint, badgeResolved)
    val badgeShape = RoundedCornerShape(10.dp)

    Row(
        modifier
            .clip(AppShapes.card)
            .background(cardSurface)
            .border(0.75.dp, cardBorder, AppShapes.card)
            .pressable(role = Role.Button, onClickLabel = "打开$title", focusShape = AppShapes.card, onClick = onClick)
            .heightIn(min = 58.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(badgeShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            tint.copy(alpha = if (palette.isDark) 0.28f else 0.16f),
                            tint.copy(alpha = if (palette.isDark) 0.14f else 0.07f),
                        ),
                    ),
                ).border(0.75.dp, badgeInk.copy(alpha = 0.18f), badgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = badgeInk, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = AppTypography.caption.regular,
                color = palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = palette.sub2,
            modifier = Modifier.size(14.dp),
        )
    }
}
