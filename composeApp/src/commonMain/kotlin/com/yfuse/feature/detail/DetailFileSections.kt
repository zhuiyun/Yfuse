package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DolbyChip
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.ServerSource

/**
 * The sections of 详情页 that describe a *file* rather than a title.
 *
 * 媒体信息, 版本, 音轨/字幕 and 资源 all answer the same underlying question — which copy
 * of this am I about to watch, and is it the good one — and they all read the same
 * [MediaVersion]. Everything left in `DetailScreen.kt` is about the work itself: its
 * artwork, its synopsis, its cast, its episodes.
 */

/**
 * 媒体信息 — everything the server knows about the file that is actually playing.
 *
 * One card per stream rather than one table for the file: a release with a 国语 and a 原声
 * track differs only in the audio, and interleaving both into a single list would make the
 * difference impossible to read. Absent fields are dropped rather than shown as 未知 — the
 * list is already long, and "the server didn't say" is not worth a row of its own.
 */
@Composable
internal fun MediaInfoSection(
    version: MediaVersion,
    dateCreated: String?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("媒体信息", Modifier.padding(horizontal = Dimens.pageHorizontal))
        // Two cards fill the width, as in the reference; a third and beyond (a release with
        // several audio tracks) scroll in from the right rather than shrinking the pair.
        BoxWithConstraints {
        val cardWidth = (maxWidth - Dimens.pageHorizontal * 2 - 10.dp) / 2
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            version.video?.let { video ->
                item(key = "video") {
                    SpecCard(
                        icon = AppIcons.Play,
                        title = "视频",
                        width = cardWidth,
                        rows = listOfNotNull(
                            video.displayTitle?.let { "显示标题" to it },
                            video.language?.let { "语言" to it },
                            video.codec?.let { "编码" to it },
                            video.resolutionLabel?.let { "分辨率" to it },
                            video.frameRateLabel?.let { "帧率" to it },
                            video.bitrateBps?.takeIf { it > 0 }
                                ?.let { "比特率" to "${it / 1_000_000} Mbps" },
                            video.videoRange?.let { "动态范围" to it },
                            video.interlaced?.let { "隔行扫描" to if (it) "是" else "否" },
                            video.colorPrimaries?.let { "色彩原色" to it },
                            video.colorSpace?.let { "色彩空间" to it },
                            video.profile?.let { "配置" to it },
                            video.level?.takeIf { it > 0 }?.let { "等级" to it.toInt().toString() },
                            video.aspectRatio?.let { "长宽比" to it },
                            video.bitDepth?.takeIf { it > 0 }?.let { "位深" to it.toString() },
                        ),
                    )
                }
            }
            itemsIndexed(version.audioTracks) { index, audio ->
                SpecCard(
                    icon = AppIcons.Volume,
                    title = if (version.audioTracks.size > 1) "音频 ${index + 1}" else "音频",
                    width = cardWidth,
                    rows = listOfNotNull(
                        audio.displayTitle?.let { "标题" to it },
                        audio.language?.let { "语言" to it },
                        audio.codec?.uppercase()?.let { "编码" to it },
                        audio.profile?.let { "配置" to it },
                        audio.bitrateLabel?.let { "比特率" to it },
                        audio.channels?.let { "布局" to it },
                        audio.channelCount?.takeIf { it > 0 }?.let { "声道" to it.toString() },
                        audio.sampleRateLabel?.let { "采样率" to it },
                        audio.external?.let { "外部" to if (it) "是" else "否" },
                        audio.default?.let { "默认" to if (it) "是" else "否" },
                        audio.displayLanguage?.let { "显示语言" to it },
                    ),
                )
            }
        }
        }
        val footer = listOfNotNull(
            version.container?.uppercase(),
            version.sizeLabel,
            dateCreated,
        )
        if (version.path != null || footer.isNotEmpty()) {
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .padding(horizontal = Dimens.pageHorizontal)
                    .fillMaxWidth()
                    .solidGlass(
                        shape = GlassShapes.card,
                        fill = if (palette.isDark) {
                            Color.White.copy(alpha = 0.05f)
                        } else {
                            Color.White.copy(alpha = 0.55f)
                        },
                        border = palette.border,
                    )
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                version.path?.let { path ->
                    Text(path, style = mr(10f, 400), color = palette.sub2)
                }
                if (footer.isNotEmpty()) {
                    Text(
                        footer.joinToString(" · "),
                        style = mr(10f, 500),
                        color = palette.sub2,
                    )
                }
            }
        }
    }
}

/** One stream's specification, as a fixed-width card of label/value rows. */
@Composable
private fun SpecCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    width: Dp,
    rows: List<Pair<String, String>>,
) {
    val palette = LocalPalette.current
    // A white palette border disappears against this card's pale surface. Keep the
    // glass card body, but give it one calm, solid outline in both themes.
    val edge = if (palette.isDark) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color(0xFF141A26).copy(alpha = 0.12f)
    }
    Column(
        Modifier
            .width(width)
            .solidGlass(
                shape = GlassShapes.card,
                fill = if (palette.isDark) {
                    Color.White.copy(alpha = 0.06f)
                } else {
                    Color.White.copy(alpha = 0.72f)
                },
                border = edge,
            )
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = palette.sub, modifier = Modifier.size(13.dp))
            Text(title, style = sc(12.5f, 700), color = palette.text)
        }
        Spacer(Modifier.height(10.dp))
        rows.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(label, style = mr(10.5f, 400), color = palette.sub2)
                Spacer(Modifier.width(10.dp))
                Text(
                    value,
                    style = mr(10.5f, 500),
                    color = palette.body,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * 版本 — which of the server's several files for this title plays.
 *
 * Only a picker now. It used to carry a 规格 summary as well, which 媒体信息 states in far
 * more detail a couple of sections further down; two accounts of the same file, one of them
 * partial, is worse than one.
 */
@Composable
internal fun VersionSection(
    versions: List<MediaVersion>,
    selectedId: String?,
    accent: Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    // Nothing to choose between, nothing to show: 媒体信息 now spells the file out in full,
    // so a 规格 summary here would state the same facts twice, less completely.
    if (versions.size <= 1) return
    val selected = versions.firstOrNull { it.id == selectedId } ?: versions.first()
    Column(modifier) {
        SectionHeader(
            title = "版本",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        ) {
            Text("${versions.size} 个版本", style = mr(10.5f, 500), color = palette.sub2)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(versions, key = { it.id }) { version ->
                VersionCard(
                    version = version,
                    selected = version.id == selected.id,
                    accent = accent,
                    onSelect = { onSelect(version.id) },
                )
            }
        }
    }
}

/**
 * 音轨 / 字幕 — which track the player should open with.
 *
 * The player has had these pickers all along; what it has not had is a way to answer the
 * question *before* the film starts. A release with a 国语 and an 原声 track opens on
 * whichever the file marks default, and finding out it was the wrong one means hearing it,
 * pausing, and going two panels deep while the room waits.
 *
 * Selection travels as a language rather than a stream number — see [PlaybackTrackRequest].
 * 默认 is a real choice and always present: it is the only one that says "I have no opinion",
 * and without it a picker that has been touched can never be untouched.
 */
@Composable
internal fun TrackSection(
    version: MediaVersion,
    audioLanguage: String?,
    subtitleLanguage: String?,
    accent: Color,
    onSelectAudio: (String?) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (version.audioTracks.size > 1) {
            Column {
                SectionHeader("音轨", Modifier.padding(horizontal = Dimens.pageHorizontal))
                TrackChipRow(
                    options = buildList {
                        add(TrackChoice(null, "默认"))
                        // A track the server tagged with no language is unreachable —
                        // language is the only handle the player has on it — so it is not
                        // offered rather than offered and silently ignored.
                        version.audioTracks.forEach { track ->
                            track.language?.let { add(TrackChoice(it, track.label)) }
                        }
                    },
                    selected = audioLanguage,
                    accent = accent,
                    onSelect = onSelectAudio,
                )
            }
        }
        if (version.subtitleTracks.isNotEmpty()) {
            Column {
                SectionHeader("字幕", Modifier.padding(horizontal = Dimens.pageHorizontal))
                TrackChipRow(
                    options = buildList {
                        add(TrackChoice(null, "默认"))
                        add(TrackChoice(PlaybackTrackRequest.SUBTITLES_OFF, "关闭"))
                        version.subtitleTracks.forEach { track ->
                            track.language?.let { add(TrackChoice(it, track.label)) }
                        }
                    },
                    selected = subtitleLanguage,
                    accent = accent,
                    onSelect = onSelectSubtitle,
                )
            }
        }
    }
}

/** One selectable track, as the value that travels and the words on the chip. */
private data class TrackChoice(val value: String?, val label: String)

@Composable
private fun TrackChipRow(
    options: List<TrackChoice>,
    selected: String?,
    accent: Color,
    onSelect: (String?) -> Unit,
) {
    val palette = LocalPalette.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Positional keys on purpose: a file can carry two tracks the server tags with the
        // same language, so the value is not unique and cannot be one.
        items(options) { option ->
            val active = option.value == selected
            Text(
                option.label,
                style = sc(11.5f, if (active) 700 else 500),
                color = if (active) accent else palette.body,
                maxLines = 1,
                modifier = Modifier
                    // Match 外部链接: the same lifted liquid-glass body in both themes.
                    // Selection changes only the text and one solid-colour edge.
                    .shadow(GlassLift.control, GlassShapes.chip)
                    .liquidGlass(
                        shape = GlassShapes.chip,
                        fill = if (palette.isDark) {
                            Color.White.copy(alpha = 0.075f)
                        } else {
                            Color.White.copy(alpha = 0.72f)
                        },
                        border = if (active) accent.copy(alpha = 0.32f) else palette.border,
                        sheen = 0.7f,
                    )
                    .pressable(onClick = { onSelect(option.value) })
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun VersionCard(
    version: MediaVersion,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
) {
    val palette = LocalPalette.current
    val edge = when {
        selected -> accent
        palette.isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color(0xFF141A26).copy(alpha = 0.10f)
    }
    Column(
        Modifier
            .width(150.dp)
            .solidGlass(
                shape = GlassShapes.card,
                fill = if (palette.isDark) {
                    Color.White.copy(alpha = 0.06f)
                } else {
                    Color.White.copy(alpha = 0.82f)
                },
                border = null,
            )
            .border(if (selected) 1.5.dp else Dimens.hairline, edge, GlassShapes.card)
            .pressable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                version.name,
                style = sc(12f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "当前版本",
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Text(
            version.qualityLabel,
            style = mr(10.5f, 500),
            color = accent,
            maxLines = 1,
        )
        Text(
            listOfNotNull(version.sizeLabel, version.bitrateLabel).joinToString(" · "),
            style = mr(10f, 400),
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SourceSection(
    sources: List<ServerSource>,
    selectedServerId: String?,
    selectedItemId: String?,
    accent: Color,
    onSelect: (serverId: String, itemId: String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val availableSources = remember(sources) {
        sources.filter { it.reachable && it.source != null && it.itemId != null }
    }
    Column(modifier) {
        SectionHeader(
            title = "资源",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        ) {
            Row(
                Modifier
                    .pressable(onClick = onSeeAll)
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${availableSources.size} 个媒体库",
                    style = mr(10.5f, 500),
                    color = palette.sub2,
                )
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = "查看全部资源",
                    tint = palette.hint,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        // The biggest file is called out, because that is the question the row exists to
        // answer: given the same title on two servers, which copy is the better one.
        val bestServerId = remember(availableSources) {
            availableSources
                .filter { it.source?.sizeBytes != null }
                .maxByOrNull { it.source?.sizeBytes ?: 0L }
                ?.takeIf { availableSources.size > 1 }
                ?.serverId
        }
        BoxWithConstraints {
            val cardWidth = (maxWidth - Dimens.pageHorizontal * 2 - 10.dp) / 2
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    availableSources,
                    key = { index, entry -> "source-${entry.serverId}-${entry.itemId}-$index" },
                ) { _, entry ->
                    SourceCard(
                        entry = entry,
                        selected = entry.serverId == selectedServerId &&
                            entry.itemId == selectedItemId,
                        accent = accent,
                        best = entry.serverId == bestServerId,
                        width = cardWidth,
                        onSelect = { entry.itemId?.let { onSelect(entry.serverId, it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    entry: ServerSource,
    selected: Boolean,
    accent: Color,
    best: Boolean,
    width: Dp,
    onSelect: () -> Unit,
) {
    val palette = LocalPalette.current
    val source = entry.source
    // 1.5dp on the selected ring, so switching sources moves the edge as well as the
    // colour. The body keeps its glass-card sheen; the edge itself is always one solid
    // colour and is drawn separately so it can become heavier for the selected source.
    val edge = when {
        selected -> accent
        palette.isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color(0xFF141A26).copy(alpha = 0.10f)
    }
    Column(
        Modifier
            .width(width)
            .solidGlass(
                shape = GlassShapes.card,
                fill = if (palette.isDark) {
                    Color.White.copy(alpha = 0.06f)
                } else {
                    Color.White.copy(alpha = 0.82f)
                },
                border = null,
            )
            .border(if (selected) 1.5.dp else Dimens.hairline, edge, GlassShapes.card)
            .pressable(onClick = onSelect)
            .padding(horizontal = 11.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(serverTint(entry.serverId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.serverName.take(1).uppercase(),
                    style = mr(10f, 700),
                    color = Color.White,
                )
            }
            Text(
                entry.serverName,
                style = sc(11.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (best) {
                Text(
                    "Best",
                    style = mr(9f, 700),
                    color = Color(0xFF9A6B12),
                    modifier = Modifier
                        .clip(GlassShapes.chip)
                        .background(Color(0xFFF5C86A).copy(alpha = 0.30f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountChip(AppIcons.Volume, source?.audioTrackCount ?: 0)
            CountChip(AppIcons.Subtitle, source?.subtitleTrackCount ?: 0)
            Spacer(Modifier.weight(1f))
            // The mark rather than the words: at this size "Dolby Vision" would take the
            // width of the rest of the row, and the mark is what the eye is scanning for.
            if (source?.dolbyVision == true) {
                DolbyChip("VISION", if (selected) accent else palette.sub)
            }
            source?.quality?.takeIf { it.isNotBlank() && source.dolbyVision != true }?.let { quality ->
                Text(
                    quality,
                    style = mr(9f, 700),
                    color = if (selected) accent else palette.sub,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(GlassShapes.chip)
                        .background(
                            if (selected) {
                                accent.copy(alpha = 0.12f)
                            } else {
                                Color(0xFF141A26).copy(alpha = 0.05f)
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                source?.size ?: "—",
                style = mr(10f, 600),
                color = palette.body,
                maxLines = 1,
            )
            Text(
                source?.bitrate ?: "—",
                style = mr(10f, 600),
                color = palette.body,
                maxLines = 1,
            )
        }
    }
}

/** `♪ 2` — a stream count small enough to sit three-to-a-row on a half-width card. */
@Composable
private fun CountChip(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    val palette = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.sub2, modifier = Modifier.size(11.dp))
        Text(count.toString(), style = mr(9.5f, 600), color = palette.sub2)
    }
}

/**
 * A stable colour per server, so the same library keeps the same tile wherever it appears.
 * Derived from the id rather than stored: servers are added and removed, and a palette
 * index would drift every time the list changed.
 */
internal fun serverTint(serverId: String): Color {
    val palette = listOf(
        Color(0xFF4C7DF0), Color(0xFF41A98A), Color(0xFFD1705C),
        Color(0xFF8B6FD1), Color(0xFFD19A3F), Color(0xFF3FA3C4),
    )
    val index = (serverId.hashCode().toLong() and 0xFFFFFFFFL) % palette.size
    return palette[index.toInt()]
}
