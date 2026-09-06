package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.Episode
import com.yfuse.core.network.EmbyImages

/** Sticky-header/footer batch editor: bottom sheet on phones, bounded dialog on larger screens. */
@Composable
internal fun EpisodeProgressManager(
    episodes: List<Episode>,
    baseUrl: String,
    accessToken: String,
    seriesPosterUrl: String?,
    selectedIds: Set<String>,
    saving: Boolean,
    accent: Color,
    onToggle: (String) -> Unit,
    onPreset: (EpisodeSelectionPreset) -> Unit,
    onApply: (EpisodeProgressAction) -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        GlassDialog(
            onDismiss = onDismiss,
            dismissEnabled = !saving,
            scrollable = false,
            contentPadding = 0.dp,
            alignment = if (compact) Alignment.BottomCenter else Alignment.Center,
            maxWidth = 680.dp,
            windowPadding = PaddingValues(horizontal = if (compact) 8.dp else 26.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxHeight(if (compact) 0.88f else 0.82f),
        ) {
            Column(Modifier.fillMaxSize()) {
                ProgressManagerHeader(
                    count = selectedIds.size,
                    saving = saving,
                    onDismiss = overlayDismiss(onDismiss),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { PresetChip("全选", accent, !saving) { onPreset(EpisodeSelectionPreset.All) } }
                    item { PresetChip("选择已看", accent, !saving) { onPreset(EpisodeSelectionPreset.Watched) } }
                    item { PresetChip("选择未看", accent, !saving) { onPreset(EpisodeSelectionPreset.Unwatched) } }
                    item { PresetChip("反选", accent, !saving) { onPreset(EpisodeSelectionPreset.Invert) } }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(episodes, key = { it.id }) { episode ->
                        ProgressEpisodeRow(
                            episode = episode,
                            baseUrl = baseUrl,
                            accessToken = accessToken,
                            seriesPosterUrl = seriesPosterUrl,
                            selected = episode.id in selectedIds,
                            accent = accent,
                            enabled = !saving,
                            onClick = { onToggle(episode.id) },
                        )
                    }
                }
                ProgressManagerActions(
                    selectionCount = selectedIds.size,
                    saving = saving,
                    accent = accent,
                    onApply = onApply,
                )
            }
        }
    }
}

@Composable
private fun ProgressManagerHeader(
    count: Int,
    saving: Boolean,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("管理观看进度", style = AppTypography.section.strong, color = palette.text)
            Text("已选择 $count 集", style = AppTypography.caption.regular, color = palette.sub2)
        }
        Box(
            Modifier
                .pressable(enabled = !saving, onClickLabel = "关闭") { onDismiss() }
                .touchTarget()
                .size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Close, contentDescription = "关闭", tint = palette.text, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Text(
        label,
        style = AppTypography.caption.strong,
        color = if (enabled) accent else palette.sub2,
        modifier =
            Modifier
                .pressable(enabled = enabled, onClick = onClick)
                .heightIn(min = 44.dp)
                .solidGlass(GlassShapes.chip, accent.copy(alpha = 0.10f), accent.copy(alpha = 0.22f))
                .padding(horizontal = 13.dp, vertical = 10.dp),
    )
}

@Composable
private fun ProgressEpisodeRow(
    episode: Episode,
    baseUrl: String,
    accessToken: String,
    seriesPosterUrl: String?,
    selected: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(
                enabled = enabled,
                role = Role.Checkbox,
                onClickLabel = "选择第${episode.indexNumber ?: "?"}集",
                onClick = onClick,
            ).semantics {
                this.selected = selected
                stateDescription = if (selected) "已选择" else "未选择"
            }.clip(GlassShapes.card)
            .background(if (selected) accent.copy(alpha = if (palette.isDark) 0.24f else 0.16f) else palette.card2)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(104.dp).height(59.dp)) {
            Poster(
                url =
                    EmbyImages.primary(
                        baseUrl,
                        episode.id,
                        episode.primaryTag,
                        maxHeight = 180,
                        accessToken = accessToken,
                    ),
                fallbackUrls = listOfNotNull(seriesPosterUrl),
                shape = GlassShapes.thumb,
                progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) {
                EpisodeSelectionBadge(accent, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(episode.indexNumber?.let { "第 $it 集" }, episode.name).joinToString(" · "),
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    episode.played -> "已看完"
                    (episode.resumePositionTicks ?: 0L) > 0L ->
                        "观看中 · ${episode.playedPercentage?.toInt() ?: 0}%"
                    else -> "未观看"
                },
                style = AppTypography.caption.regular,
                color = if (selected) accent else palette.sub2,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProgressManagerActions(
    selectionCount: Int,
    saving: Boolean,
    accent: Color,
    onApply: (EpisodeProgressAction) -> Unit,
) {
    val palette = LocalPalette.current
    val enabled = selectionCount > 0 && !saving
    Row(
        Modifier.fillMaxWidth().background(palette.card).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (saving) {
            CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
            Text(
                "正在同步…",
                style = AppTypography.body.strong,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
        } else {
            ProgressAction("标记已看", enabled, accent, Modifier.weight(1f)) {
                onApply(EpisodeProgressAction.MarkWatched)
            }
            ProgressAction("标记未看", enabled, accent, Modifier.weight(1f)) {
                onApply(EpisodeProgressAction.MarkUnwatched)
            }
            ProgressAction("重置", enabled, accent, Modifier.weight(1f)) {
                onApply(EpisodeProgressAction.Reset)
            }
        }
    }
}

@Composable
private fun ProgressAction(
    label: String,
    enabled: Boolean,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .heightIn(min = 48.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .solidGlass(
                GlassShapes.thumb,
                if (enabled) accent.copy(alpha = 0.16f) else palette.card2,
                if (enabled) accent.copy(alpha = 0.30f) else palette.border,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppTypography.caption.strong, color = if (enabled) accent else palette.sub2)
    }
}
