package com.yfuse.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.navigationGlass

internal data class TabItem(
    val tab: Tab,
    val label: String,
    val icon: ImageVector,
)

internal val LocalWideWindow = staticCompositionLocalOf { false }
internal val RailContentInset = 96.dp

@Composable
internal fun AdaptiveNavigationRail(
    items: List<TabItem>,
    active: Tab,
    backdrop: BackdropState,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        modifier
            .width(RailContentInset)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .navigationGlass(backdrop, AppShapes.sheet)
            .selectableGroup()
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (item in items) {
            val selected = active == item.tab
            val ink = if (selected) accent.accent else palette.text
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(if (selected) Modifier.liquidGlass(AppShapes.chip, accent.container) else Modifier)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.tab) })
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ThemeIcon(item.icon, null, tint = ink, modifier = Modifier.size(22.dp))
                ThemeText(item.label, color = ink, style = AppTypography.caption.medium, maxLines = 1)
            }
        }
    }
}
