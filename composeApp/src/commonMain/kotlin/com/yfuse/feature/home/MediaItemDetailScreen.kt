package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.TgtoEmbyCardStatus
import com.yfuse.core.data.TgtoMediaItem
import com.yfuse.core.data.TgtoResourceItem
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget

@Composable
fun MediaItemDetailScreen(component: MediaItemDetailComponent) {
    val state by component.state.collectAsState()
    val item = state.item
    val palette = LocalPalette.current
    var confirmTransfer by remember { mutableStateOf<TgtoResourceItem?>(null) }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Dimens.contentBottom),
        ) {
            item(key = "media-detail-hero") {
                MediaItemDetailHero(item = item, onBack = component.onBack)
            }
            item(key = "media-detail-content") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.pageHorizontal, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    MediaItemFacts(item, state.embyStatus)
                    when {
                        state.localLibraryLoading ->
                            OverlayButton(
                                label = "正在匹配本地媒体库",
                                onClick = {},
                                enabled = false,
                                loading = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        state.localItemId != null ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OverlayButton(
                                    label = "播放",
                                    onClick = component::playInYfuse,
                                    tone = OverlayButtonTone.Primary,
                                    modifier = Modifier.weight(1f),
                                )
                                OverlayButton(
                                    label = "媒体详情",
                                    onClick = component::openLibraryDetail,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        else ->
                            OverlayButton(
                                label = "在 Yfuse 中查看",
                                onClick = component::openInYfuse,
                                tone = OverlayButtonTone.Primary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                    }
                    state.navigationError?.let {
                        DetailCallout(it, error = true, onClick = component::dismissMessage)
                    }
                    if (item.overview.isNotBlank()) {
                        DetailSection(title = "剧情简介") {
                            Text(item.overview, style = AppTypography.body.regular, color = palette.body)
                        }
                    }
                    if (item.genres.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item.genres.distinct().forEach { genre ->
                                Text(
                                    genre,
                                    style = AppTypography.caption.medium,
                                    color = palette.sub,
                                    modifier =
                                        Modifier
                                            .solidGlass(GlassShapes.chip)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Media123Resources(
                        resources = state.resources,
                        loading = state.resourcesLoading,
                        error = state.resourcesError,
                        targetConfigured = state.target123Configured,
                        targetName = state.target123Name,
                        transferringKey = state.transferringKey,
                        transferMessage = state.transferMessage,
                        onDismissMessage = component::dismissMessage,
                        onTransfer = { confirmTransfer = it },
                    )
                }
            }
        }
    }

    confirmTransfer?.let { resource ->
        ConfirmDialog(
            title = "转存到 123 云盘",
            message = "确认将“${resource.title}”转存到“${state.target123Name.ifBlank { "123 保存目录" }}”吗？",
            confirmLabel = "转存",
            onConfirm = {
                confirmTransfer = null
                component.transfer(resource)
            },
            onDismiss = { confirmTransfer = null },
        )
    }
}

@Composable
private fun MediaItemDetailHero(
    item: TgtoMediaItem,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        FallbackImage(
            urls = listOf(item.backdropUrl, item.posterUrl),
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)))
        Icon(
            AppIcons.ChevronLeft,
            contentDescription = "返回",
            tint = Color.White,
            modifier =
                Modifier
                    .statusBarsPadding()
                    .padding(start = Dimens.pageHorizontal, top = 10.dp)
                    .pressable(onClickLabel = "返回影视发现", onClick = onBack)
                    .touchTarget()
                    .size(40.dp)
                    .solidGlass(
                        shape = CircleShape,
                        fill = Color.Black.copy(alpha = 0.28f),
                        border = Color.White.copy(alpha = 0.28f),
                    ).padding(10.dp),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Poster(
                url = item.posterUrl,
                fallbackUrl = item.backdropUrl,
                rating = item.score,
                modifier = Modifier.width(92.dp).height(138.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    item.title,
                    style = AppTypography.display.strong,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata =
                    listOfNotNull(
                        item.year.takeIf(String::isNotBlank),
                        item.normalizedMediaType.takeIf(String::isNotBlank)?.let { if (it == "tv") "剧集" else "电影" },
                        item.runtime?.takeIf { it > 0 }?.let { "$it 分钟" },
                    ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(metadata, style = AppTypography.caption.medium, color = Color.White.copy(alpha = 0.78f))
                }
            }
        }
    }
}

@Composable
private fun MediaItemFacts(
    item: TgtoMediaItem,
    embyStatus: TgtoEmbyCardStatus?,
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item.tagline.takeIf(String::isNotBlank)?.let {
            Text(it, style = AppTypography.body.strong, color = palette.text)
        }
        embyStatus?.detailLabel(item.normalizedMediaType)?.let { label ->
            val tone =
                when {
                    embyStatus.state == "not-found" || embyStatus.state == "error" -> palette.error
                    embyStatus.libraryStatus == "missing" -> Semantic.Warning
                    else -> Semantic.Success
                }
            Text(
                label,
                style = AppTypography.caption.strong,
                color = tone,
                modifier =
                    Modifier
                        .flatGlass(GlassShapes.chip, palette.card2, tone.copy(alpha = 0.42f))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = AppTypography.section.strong, color = palette.text)
        content()
    }
}

@Composable
private fun Media123Resources(
    resources: List<TgtoResourceItem>,
    loading: Boolean,
    error: String?,
    targetConfigured: Boolean,
    targetName: String,
    transferringKey: String?,
    transferMessage: String?,
    onDismissMessage: () -> Unit,
    onTransfer: (TgtoResourceItem) -> Unit,
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("123 云盘资源", style = AppTypography.section.strong, color = palette.text)
                Text(
                    if (targetConfigured) "转存到 ${targetName.ifBlank { "已配置目录" }}" else "尚未配置 123 保存目录",
                    style = AppTypography.caption.regular,
                    color = if (targetConfigured) palette.sub2 else palette.error,
                )
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = LocalAccentColors.current.accent)
        }
        transferMessage?.let { DetailCallout(it, error = false, onClick = onDismissMessage) }
        error?.let { DetailCallout(it, error = true) }
        when {
            loading -> Unit
            resources.isEmpty() -> PageHint("当前 123 公开频道没有匹配资源", icon = AppIcons.Cloud)
            else ->
                resources.forEach { resource ->
                    Media123ResourceRow(
                        resource = resource,
                        targetConfigured = targetConfigured,
                        transferring = transferringKey == resource.itemKey,
                        onTransfer = { onTransfer(resource) },
                    )
                }
        }
    }
}

@Composable
private fun Media123ResourceRow(
    resource: TgtoResourceItem,
    targetConfigured: Boolean,
    transferring: Boolean,
    onTransfer: () -> Unit,
) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .flatGlass(GlassShapes.card, palette.card2, palette.border)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(resource.title, style = AppTypography.body.strong, color = palette.text, maxLines = 3)
        Text(
            listOf(resource.size, resource.channelTitle.ifBlank { resource.sharer })
                .filter(String::isNotBlank)
                .joinToString(" · "),
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (resource.resourceSpecTags + resource.subtitleLanguages).distinct().take(8).forEach { tag ->
                Text(
                    tag,
                    style = AppTypography.caption.medium,
                    color = LocalAccentColors.current.accent,
                    modifier = Modifier.solidGlass(GlassShapes.chip).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayButton(
                label =
                    if (transferring) {
                        "转存中"
                    } else if (targetConfigured) {
                        "转存到 123"
                    } else {
                        "目录未配置"
                    },
                onClick = onTransfer,
                tone = OverlayButtonTone.Primary,
                enabled = targetConfigured && !transferring,
                loading = transferring,
                modifier = Modifier.weight(1f),
            )
            val messageUrl = resource.messageUrl.ifBlank { resource.resourceUrl }
            if (messageUrl.isNotBlank()) {
                OverlayButton(
                    label = "查看消息",
                    onClick = { runCatching { uriHandler.openUri(messageUrl) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailCallout(
    text: String,
    error: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Text(
        text,
        style = AppTypography.caption.regular,
        color = if (error) palette.error else palette.sub,
        modifier =
            Modifier
                .fillMaxWidth()
                .let { base -> if (onClick != null) base.pressable(onClick = onClick) else base }
                .flatGlass(GlassShapes.card, palette.card2, palette.border)
                .padding(12.dp),
    )
}

private fun TgtoEmbyCardStatus.detailLabel(mediaType: String): String? =
    when (state) {
        "not-found" -> "Emby 未入库"
        "error" -> "Emby 状态查询失败"
        "found" ->
            when {
                mediaType != "tv" -> "Emby 已入库"
                libraryStatus == "missing" -> "Emby 已入库 $availableCount 集 · 缺失 $missingCount 集"
                availableCount > 0 -> "Emby 已入库 $availableCount 集"
                displayLabel.isNotBlank() -> displayLabel
                else -> "Emby 已入库"
            }
        else -> null
    }
