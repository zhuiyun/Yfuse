package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * One heading scale for every shelf/section title in the app — title on the left, an optional
 * trailing [actionLabel] on the right, its chevron drawn as a decorative [AppIcons.ChevronRight]
 * rather than baked into the label as a "›" glyph.
 *
 * [actionLabel] alone (no [onAction]) reads as a plain, non-interactive readout — a count like
 * "3 个版本" — and draws no chevron, since nothing is reachable from it. Passing both makes the
 * whole label pressable and adds the chevron, the "全部"/"更多" shape most callers want.
 *
 * Consolidates call sites that had drifted into two different labels for the same action
 * (media library's "更多" vs 首页/详情's "全部 ›") and duplicated this header row each time
 * (`detail/DetailSections.kt`, `detail/DetailFileSections.kt`, `library/LibraryHomeScreen.kt`).
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth().padding(bottom = Dimens.space.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = AppTypography.section.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (actionLabel != null) {
            Row(
                Modifier
                    .let {
                        if (onAction != null) {
                            it.pressable(onClickLabel = "查看${title}的全部内容", onClick = onAction).touchTarget()
                        } else {
                            it
                        }
                    }.padding(start = Dimens.space.sm, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, style = AppTypography.caption.medium, color = palette.sub2)
                if (onAction != null) {
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = palette.hint,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}
