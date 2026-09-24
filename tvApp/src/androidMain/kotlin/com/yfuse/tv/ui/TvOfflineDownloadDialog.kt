package com.yfuse.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.offline.OfflineBatchItem
import com.yfuse.core.offline.OfflineBatchMode
import com.yfuse.core.offline.OfflineDownloadSelection
import com.yfuse.core.offline.estimateOfflineDownloadBytes
import com.yfuse.core.offline.selectOfflineBatchItems
import com.yfuse.feature.detail.OfflineDownloadDraft
import com.yfuse.feature.profile.formatDownloadBytes
import com.yfuse.tv.focus.requestFocusWhenAttached
import com.yfuse.tv.focus.tvFocusScope

@Composable
internal fun TvOfflineDownloadDialog(
    detail: MediaDetail,
    episodes: List<Episode>,
    selectedVersionId: String?,
    focusMemory: TvUiFocusMemory,
    onConfirm: (OfflineDownloadSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(detail.id, selectedVersionId) {
        mutableStateOf(OfflineDownloadDraft(versionId = selectedVersionId))
    }
    val selection = draft.originalSelection(detail.versions)
    val version = detail.versions.firstOrNull { it.id == selection.mediaSourceId }
    val subtitle = version?.subtitleTracks?.firstOrNull { it.index == selection.subtitleStreamIndex }
    val count =
        selectOfflineBatchItems(
            draft.batchMode,
            detail.id,
            episodes.map {
                OfflineBatchItem(it.id, it.played)
            },
        ).size
    val estimate =
        estimateOfflineDownloadBytes(
            detail.id,
            detail.title,
            detail.runtimeMinutes,
            detail.versions,
            episodes,
            selection,
        )
    val firstRequester = remember { FocusRequester() }
    val focusScope = "detail:offline:${detail.id}"
    LaunchedEffect(Unit) { firstRequester.requestFocusWhenAttached() }
    GlassDialog(onDismiss = onDismiss, maxWidth = 760.dp, contentPadding = 26.dp) {
        // Both ways out finish the exit before the sheet goes, as back and the scrim already did.
        val confirm = overlayAction { onConfirm(selection) }
        val cancel = overlayDismiss(onDismiss)
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("离线下载", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.ExtraBold)
            Text(
                "${detail.title} · $count 项 · ${estimate?.let(::formatDownloadBytes) ?: "空间待服务器确认"}",
                color = TvOnSurface.copy(alpha = 0.7f),
                fontSize = TvType.caption,
            )
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    TvSettingRow(
                        title = "下载范围",
                        value = draft.batchMode.label,
                        stableId = "offline:range",
                        focusMemory = focusMemory,
                        onClick = {
                            if (episodes.isNotEmpty()) {
                                draft =
                                    draft.copy(
                                        batchMode =
                                            OfflineBatchMode.entries[
                                                (draft.batchMode.ordinal + 1) %
                                                    OfflineBatchMode.entries.size,
                                            ],
                                    )
                            }
                        },
                        icon = AppIcons.Download,
                        focusScope = focusScope,
                        focusRequester = firstRequester,
                        subtitle = if (episodes.isEmpty()) "本片" else "按确定切换：本集、整季、仅未看集",
                    )
                }
                item {
                    TvSettingRow(
                        title = "原画版本",
                        value =
                            version?.name?.ifBlank {
                                version.qualityLabel
                            } ?: "服务器默认",
                        stableId = "offline:version",
                        focusMemory = focusMemory,
                        onClick = {
                            val versions = detail.versions
                            if (versions.isNotEmpty()) {
                                draft =
                                    draft.selectVersion(
                                        versions[
                                            (
                                                versions.indexOfFirst { it.id == selection.mediaSourceId } +
                                                    1
                                            ) %
                                                versions.size,
                                        ].id,
                                    )
                            }
                        },
                        icon = AppIcons.Movie,
                        focusScope = focusScope,
                        subtitle = "电视下载保留原画，不创建服务器转码任务。整季下载会匹配每集对应版本。",
                    )
                }
                item {
                    TvSettingRow(
                        title = "外挂字幕",
                        value = subtitle?.label ?: "不下载字幕",
                        stableId = "offline:subtitle",
                        focusMemory = focusMemory,
                        onClick = {
                            val indices = listOf<Int?>(null) + version?.subtitleTracks.orEmpty().mapNotNull { it.index }
                            draft =
                                draft.copy(
                                    subtitleIndex =
                                        indices[
                                            (indices.indexOf(draft.subtitleIndex) + 1).coerceAtLeast(0) %
                                                indices.size,
                                        ],
                                )
                        },
                        icon = AppIcons.Subtitle,
                        focusScope = focusScope,
                        subtitle = "按确定切换；切换版本后需重新选择字幕。",
                    )
                }
                if (detail.seriesId != null) {
                    item {
                        TvToggleRow(
                            title = "自动下载后续新集",
                            checked = draft.followNewEpisodes,
                            stableId = "offline:follow",
                            focusMemory = focusMemory,
                            onToggle = { draft = draft.copy(followNewEpisodes = it) },
                            icon = AppIcons.Bell,
                            focusScope = focusScope,
                            subtitle = "遵循下载管理中的 Wi-Fi、充电、时间窗和容量预算。",
                        )
                    }
                }
            }
            TvSettingRow(
                title =
                    if (count ==
                        0
                    ) {
                        "没有未看集可下载"
                    } else {
                        "加入 $count 项下载"
                    },
                value = "",
                stableId = "offline:confirm",
                focusMemory = focusMemory,
                onClick = confirm,
                icon = AppIcons.Download,
                focusScope = focusScope,
                enabled =
                    count > 0,
            )
            TvSettingRow("取消", "", "offline:cancel", focusMemory, cancel, AppIcons.Close, focusScope = focusScope)
        }
    }
}
