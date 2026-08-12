package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.ServerSource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size

internal fun playbackVersionSummary(
    serverName: String?,
    version: MediaVersion?,
    audioLanguage: String?,
    subtitleLanguage: String?,
): String = listOfNotNull(
    serverName?.takeIf { it.isNotBlank() },
    version?.qualityLabel,
    audioLanguage?.takeIf { it.isNotBlank() }?.let { "$it 音轨" },
    when (subtitleLanguage) {
        PlaybackTrackRequest.SUBTITLES_OFF -> "字幕关闭"
        null -> null
        else -> "$subtitleLanguage 字幕"
    },
).joinToString(" · ").ifBlank { "自动选择最佳播放版本" }

@Composable
internal fun PlaybackVersionSection(
    summary: String,
    switching: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .pressable(onClick = onClick)
            .glass(GlassShapes.card, palette.card2, palette.border)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("版本与来源", style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(3.dp))
            Text(
                if (switching) "正在切换资源…" else summary,
                style = AppTypography.caption.medium,
                color = palette.sub,
                maxLines = 2,
            )
        }
        Text("›", style = AppTypography.section.medium, color = palette.sub2)
    }
}

/** Wide enough for a two-column fact table without wrapping `42.3 GB`. */
private val ComparisonCardWidth = 176.dp

/**
 * Best first, the current choice second, the rest in their existing rank.
 *
 * The list arrives already ranked, so the top of it is the honest answer to "which is the
 * best copy". The one you are *on* is the other thing worth seeing without scrolling — a
 * comparison is between two things, and the second of them was previously wherever the
 * ranking happened to put it, which on a server with six copies was off the end of the row.
 * When the current choice is already first or second there is nothing to move.
 */
internal fun <T> List<T>.bestThenSelectedFirst(isSelected: (T) -> Boolean): List<T> {
    if (size < 3) return this
    val selectedIndex = indexOfFirst(isSelected)
    if (selectedIndex <= 1) return this
    return toMutableList().apply { add(1, removeAt(selectedIndex)) }
}

@Composable
internal fun PlaybackVersionDialog(
    title: String,
    sources: List<ServerSource>,
    selectedServerId: String?,
    selectedItemId: String?,
    versions: List<MediaVersion>,
    selectedVersionId: String?,
    selectedAudioLanguage: String?,
    selectedSubtitleLanguage: String?,
    switching: Boolean,
    onSelectSource: (String, String) -> Unit,
    onSelectVersion: (String) -> Unit,
    onSelectAudio: (String?) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "版本与来源",
            subtitle = if (switching) "正在解析所选资源" else title,
            onClose = onDismiss,
        )
        val selectableSources = sources
            .filter { it.reachable && it.itemId != null && it.source != null }
            .bestThenSelectedFirst {
                it.serverId == selectedServerId && it.itemId == selectedItemId
            }
        // Drawn for a single source too. The rail used to need two before it appeared,
        // which is right for a *comparison* and wrong for this dialog: most titles live on
        // one server with one file, so the commonest case was 「版本与来源」 opening with
        // nothing above the track lists and no answer to "which copy is this".
        if (selectableSources.isNotEmpty()) {
            ComparisonRail(
                label = if (selectableSources.size > 1) "播放来源" else "来源",
                items = selectableSources,
                key = { "${it.serverId}:${it.itemId}" },
                selected = { it.serverId == selectedServerId && it.itemId == selectedItemId },
                onSelect = { source -> source.itemId?.let { onSelectSource(source.serverId, it) } },
            ) { source ->
                val info = source.source
                ComparisonCardBody(
                    name = source.serverName,
                    headline = info?.quality.orEmpty().ifBlank { "未知清晰度" },
                    facts = listOfNotNull(
                        info?.size?.let { "体积" to it },
                        info?.bitrate?.let { "码率" to it },
                        info?.frameRate?.let { "帧率" to it },
                        info?.audioTrackCount?.takeIf { it > 0 }?.let { "音轨" to "$it 条" },
                        info?.subtitleTrackCount?.takeIf { it > 0 }?.let { "字幕" to "$it 条" },
                    ),
                    tags = listOfNotNull(
                        info?.rangeLabel?.takeIf { it.isNotBlank() },
                        "Dolby Vision".takeIf { info?.dolbyVision == true },
                        "Atmos".takeIf { info?.dolbyAtmos == true },
                        "无损".takeIf { info?.losslessAudio == true },
                    ),
                )
            }
        }

        val orderedVersions = versions.bestThenSelectedFirst { it.id == selectedVersionId }
        if (orderedVersions.isNotEmpty()) {
            ComparisonRail(
                label = if (orderedVersions.size > 1) "文件版本" else "文件",
                items = orderedVersions,
                key = { it.id },
                selected = { it.id == selectedVersionId },
                onSelect = { onSelectVersion(it.id) },
            ) { version ->
                ComparisonCardBody(
                    name = version.name.ifBlank { version.container?.uppercase() ?: "未命名版本" },
                    headline = version.qualityLabel,
                    facts = listOfNotNull(
                        version.sizeLabel?.let { "体积" to it },
                        version.bitrateLabel?.let { "码率" to it },
                        version.videoCodec?.takeIf { it.isNotBlank() }?.let { "编码" to it.uppercase() },
                        version.container?.takeIf { it.isNotBlank() }?.let { "封装" to it.uppercase() },
                        version.audioTracks.size.takeIf { it > 0 }?.let { "音轨" to "$it 条" },
                        version.subtitleTracks.size.takeIf { it > 0 }?.let { "字幕" to "$it 条" },
                    ),
                    tags = listOfNotNull(
                        version.rangeLabel,
                        "Atmos".takeIf { version.hasDolbyAtmos },
                    ),
                )
            }
        }

        val version = versions.firstOrNull { it.id == selectedVersionId } ?: versions.firstOrNull()
        version?.audioTracks?.takeIf { it.size > 1 }?.let { tracks ->
            GroupLabel("音轨")
            OverlayOptionRow("文件默认", selectedAudioLanguage == null, onClick = { onSelectAudio(null) })
            tracks.forEach { track ->
                OverlayOptionRow(track.label, track.language == selectedAudioLanguage, onClick = { onSelectAudio(track.language) })
            }
        }
        version?.subtitleTracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
            GroupLabel("字幕")
            OverlayOptionRow("文件默认", selectedSubtitleLanguage == null, onClick = { onSelectSubtitle(null) })
            OverlayOptionRow("关闭字幕", selectedSubtitleLanguage == PlaybackTrackRequest.SUBTITLES_OFF, onClick = { onSelectSubtitle(PlaybackTrackRequest.SUBTITLES_OFF) })
            tracks.forEach { track ->
                OverlayOptionRow(track.label, track.language == selectedSubtitleLanguage, onClick = { onSelectSubtitle(track.language) })
            }
        }
    }
}

/**
 * A row of copies of the same thing, side by side.
 *
 * These were stacked option rows, each collapsing a file to one ellipsized line — which is
 * a picker, not a comparison. Nothing in a vertical list lets the eye run down 体积 across
 * every candidate, and that is the whole question being asked here. Cards in a row put the
 * same fact in the same place on each card, so the differences line up.
 */
@Composable
private fun <T> ComparisonRail(
    label: String,
    items: List<T>,
    key: (T) -> Any,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    card: @Composable (T) -> Unit,
) {
    GroupLabel(label)
    LazyRow(
        // A little room at the trailing edge so the last card does not sit flush against
        // the dialog's own padding and read as the end of the list when it is not.
        contentPadding = PaddingValues(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            ComparisonCard(
                // The list is ranked, so "best" is a position, not a judgement made here —
                // and with nothing to rank against, "最佳" would be an award for turning up.
                best = index == 0 && items.size > 1,
                selected = selected(item),
                onClick = { onSelect(item) },
            ) {
                card(item)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ComparisonCard(
    best: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier
            .width(ComparisonCardWidth)
            .pressable(onClick = onClick)
            .glass(
                shape = GlassShapes.card,
                fill = if (selected) accent.container else palette.card2,
                border = if (selected) accent.border else palette.border,
            )
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (best) {
                CardBadge("最佳", accent.accent, accent.container, accent.border)
            }
            if (selected) {
                Spacer(Modifier.weight(1f))
                Icon(
                    AppIcons.Check,
                    contentDescription = "已选择",
                    tint = accent.accent,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** The name, the one headline fact, the tags, then the aligned table. */
@Composable
private fun ComparisonCardBody(
    name: String,
    headline: String,
    facts: List<Pair<String, String>>,
    tags: List<String>,
) {
    val palette = LocalPalette.current
    Text(
        name,
        style = AppTypography.caption.medium,
        color = palette.sub2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        headline,
        style = AppTypography.body.strong,
        color = palette.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (tags.isNotEmpty()) {
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Two is what fits on this width; a third would ellipsize into nothing useful.
            tags.take(2).forEach { tag ->
                CardBadge(tag, palette.sub, palette.card3, palette.border)
            }
        }
    }
    Spacer(Modifier.height(9.dp))
    facts.forEach { (fact, value) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(fact, style = AppTypography.caption.regular, color = palette.sub2)
            Text(
                value,
                style = AppTypography.caption.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardBadge(label: String, ink: Color, fill: Color, border: Color) {
    Text(
        label,
        style = AppTypography.caption.strong,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .glass(GlassShapes.chip, fill, border)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun GroupLabel(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        style = AppTypography.caption.strong,
        // Was hard-coded white, which on the light theme's near-white dialog was a label
        // nobody could read.
        color = LocalPalette.current.sub2,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
