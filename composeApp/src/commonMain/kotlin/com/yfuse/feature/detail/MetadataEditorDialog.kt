package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.EditableMetadata
import com.yfuse.core.data.MetadataArtwork
import com.yfuse.core.data.MetadataDraft
import com.yfuse.core.data.MetadataEditorService
import com.yfuse.core.data.plexArtworkTag
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun MetadataEditorDialog(
    server: SavedServer,
    itemId: String,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val service = remember { GlobalContext.get().get<MetadataEditorService>() }
    var original by remember(server.id, itemId) { mutableStateOf<EditableMetadata?>(null) }
    var draft by remember(server.id, itemId) { mutableStateOf(MetadataDraft("", "")) }
    var images by remember { mutableStateOf<List<MetadataArtwork>?>(null) }
    var selected by remember { mutableStateOf<MetadataArtwork?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current

    suspend fun operation(block: suspend () -> Unit) {
        busy = true
        error = null
        notice = null
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error =
                if (failure is IllegalArgumentException || failure is IllegalStateException) {
                    failure.message
                } else {
                    failure.toUserMessage("操作失败，请检查服务器编辑权限后重试")
                }
        } finally {
            busy = false
        }
    }
    LaunchedEffect(server.id, itemId, reload) {
        operation {
            val loaded = service.load(server, itemId)
            original = loaded
            draft = loaded.draft
        }
    }
    GlassDialog(onDismiss = { if (!busy) onDismiss() }) {
        OverlayHeader("编辑元数据", "修改将保存到 ${server.serverName}，需要服务器编辑权限")
        if (original != null) {
            MetadataField("标题", draft.title, 500, true, busy) { draft = draft.copy(title = it) }
            MetadataField("简介", draft.overview, 20_000, false, busy) { draft = draft.copy(overview = it) }
            if (server.kind != MediaServerKind.Plex) {
                MetadataField("TMDB ID", draft.tmdbId, 20, true, busy) { draft = draft.copy(tmdbId = it) }
            }
            OverlayOptionRow(if (busy) "处理中…" else "保存文字信息", false, {
                val before = original
                if (!busy && before != null) {
                    scope.launch {
                        operation {
                            service.save(server, before, draft)
                            // The write succeeded even if a later read fails. Refresh the detail now.
                            onChanged()
                            original = before.copy(draft = draft.copy(title = draft.title.trim()))
                            notice = "文字信息已保存"
                        }
                    }
                }
            })
            listOf("Primary" to "选择海报", "Backdrop" to "选择背景图").forEach { (type, title) ->
                OverlayOptionRow(title, false, {
                    if (!busy) {
                        scope.launch {
                            operation {
                                images = service.artwork(server, itemId, type)
                                selected =
                                    null
                            }
                        }
                    }
                })
            }
            images?.let { candidates ->
                if (candidates.isEmpty()) Text("服务器暂无可选图片", style = AppTypography.caption.regular, color = palette.sub)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(candidates) { artwork ->
                        val preview =
                            if (server.kind == MediaServerKind.Plex) {
                                EmbyImages.primary(
                                    server.baseUrl,
                                    itemId,
                                    artwork.preview.plexArtworkTag(),
                                    240,
                                    server.accessToken,
                                )
                            } else {
                                artwork.preview
                            }
                        Column(
                            Modifier
                                .width(if (artwork.type == "Primary") 110.dp else 190.dp)
                                .pressable(onClick = { if (!busy) selected = artwork })
                                .flatGlass(
                                    AppShapes.card,
                                    palette.card2,
                                    if (selected ==
                                        artwork
                                    ) {
                                        palette.text
                                    } else {
                                        palette.border
                                    },
                                ).padding(6.dp),
                        ) {
                            FallbackImage(listOf(preview), artwork.label, Modifier.fillMaxWidth().height(145.dp))
                            Text(
                                if (selected == artwork) "已选 · ${artwork.label}" else artwork.label,
                                style = AppTypography.caption.regular,
                                color = palette.text,
                                maxLines = 1,
                            )
                        }
                    }
                }
                selected?.let { artwork ->
                    OverlayOptionRow("应用所选图片", false, {
                        if (!busy) {
                            scope.launch {
                                operation {
                                    service.selectArtwork(server, itemId, artwork)
                                    onChanged()
                                    selected = null
                                    notice = "图片已更新"
                                }
                            }
                        }
                    })
                }
            }
        } else if (!busy) {
            OverlayOptionRow("重新读取", false, { reload += 1 })
        }
        if (busy) Text("正在与服务器通信…", style = AppTypography.caption.regular, color = palette.sub)
        error?.let { Text(it, style = AppTypography.caption.regular, color = palette.error) }
        notice?.let { Text(it, style = AppTypography.caption.regular, color = palette.text) }
        OverlayOptionRow("关闭", false, { if (!busy) onDismiss() })
    }
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    limit: Int,
    singleLine: Boolean,
    busy: Boolean,
    onChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Text(label, style = AppTypography.caption.strong, color = palette.sub)
    BasicTextField(
        value,
        { onChange(it.take(limit)) },
        enabled = !busy,
        textStyle = AppTypography.body.medium.copy(color = palette.text),
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .flatGlass(AppShapes.card, palette.card2, palette.border)
                .padding(12.dp),
    )
}
