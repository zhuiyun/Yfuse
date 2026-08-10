package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.ServerSource

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
            Text("播放版本", style = sc(13f, 700), color = palette.text)
            Spacer(Modifier.height(3.dp))
            Text(
                if (switching) "正在切换资源…" else summary,
                style = mr(11f, 500),
                color = palette.sub,
                maxLines = 2,
            )
        }
        Text("›", style = sc(18f, 500), color = palette.sub2)
    }
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
            title = "播放版本",
            subtitle = if (switching) "正在解析所选资源" else title,
            onClose = onDismiss,
        )
        val selectableSources = sources.filter { it.reachable && it.itemId != null && it.source != null }
        if (selectableSources.size > 1) {
            Text("播放来源", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            selectableSources.forEach { source ->
                OverlayOptionRow(
                    label = source.serverName,
                    selected = source.serverId == selectedServerId && source.itemId == selectedItemId,
                    onClick = { source.itemId?.let { onSelectSource(source.serverId, it) } },
                )
            }
        }
        if (versions.size > 1) {
            Text("文件版本", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            versions.forEach { version ->
                OverlayOptionRow(
                    label = listOf(version.name, version.summary).filter { it.isNotBlank() }.joinToString(" · "),
                    selected = version.id == selectedVersionId,
                    onClick = { onSelectVersion(version.id) },
                )
            }
        }
        val version = versions.firstOrNull { it.id == selectedVersionId } ?: versions.firstOrNull()
        version?.audioTracks?.takeIf { it.size > 1 }?.let { tracks ->
            Text("音轨", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            OverlayOptionRow("文件默认", selectedAudioLanguage == null, onClick = { onSelectAudio(null) })
            tracks.forEach { track ->
                OverlayOptionRow(track.label, track.language == selectedAudioLanguage, onClick = { onSelectAudio(track.language) })
            }
        }
        version?.subtitleTracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
            Text("字幕", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            OverlayOptionRow("文件默认", selectedSubtitleLanguage == null, onClick = { onSelectSubtitle(null) })
            OverlayOptionRow("关闭字幕", selectedSubtitleLanguage == PlaybackTrackRequest.SUBTITLES_OFF, onClick = { onSelectSubtitle(PlaybackTrackRequest.SUBTITLES_OFF) })
            tracks.forEach { track ->
                OverlayOptionRow(track.label, track.language == selectedSubtitleLanguage, onClick = { onSelectSubtitle(track.language) })
            }
        }
    }
}
