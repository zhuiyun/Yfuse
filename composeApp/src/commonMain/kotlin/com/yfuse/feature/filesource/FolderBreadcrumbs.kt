package com.yfuse.feature.filesource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget

@Composable
internal fun FolderBreadcrumbs(
    root: String,
    path: List<String>,
    onNavigate: (Int) -> Unit,
) {
    val accent = LocalAccentColors.current
    LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(listOf(root) + path) { depth, name ->
            ThemeText(
                (if (depth == 0) "" else "› ") + name,
                color = accent.accent,
                style = AppTypography.caption.medium,
                modifier =
                    Modifier
                        .pressable(
                            enabled = depth < path.size,
                            onClickLabel = "返回 $name",
                            onClick = { onNavigate(depth) },
                        ).touchTarget()
                        .padding(horizontal = 8.dp),
            )
        }
    }
}
