package com.yfuse.feature.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.iptv.IptvChannel
import com.yfuse.core.iptv.IptvRepository
import com.yfuse.core.iptv.IptvSubscription
import com.yfuse.core.iptv.rememberM3uPicker
import com.yfuse.core.playback.PlayerCustomHeaders
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.servers.ServerFormInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun IptvEntry() {
    var open by remember { mutableStateOf(false) }
    OverlayActionRow(label = "IPTV · 电视直播", description = "M3U 订阅、频道分组和收藏", onClick = { open = true })
    if (open) IptvDialog(onDismiss = { open = false })
}

@Composable
private fun IptvDialog(onDismiss: () -> Unit) {
    val repository =
        remember {
            org.koin.core.context.GlobalContext
                .get()
                .get<IptvRepository>()
        }
    val subscriptions by repository.subscriptions.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var launch by remember { mutableStateOf<List<PlayerMediaItem>?>(null) }
    var start by remember { mutableStateOf(0) }
    var removing by remember { mutableStateOf<IptvSubscription?>(null) }
    val palette = LocalPalette.current

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message =
                    if (error is IllegalArgumentException) error.message else "操作失败，已保留原有频道。请检查网络后重试。"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            repository.ensureLoaded()
        } catch (
            error: CancellationException,
        ) {
            throw error
        } catch (_: Exception) {
            message = "无法读取订阅，请重试"
        }
    }
    val importFile =
        rememberM3uPicker { result ->
            result.fold(onSuccess = { text ->
                perform {
                    selectedId = repository.add(name.ifBlank { "本地 M3U" }, text = text)
                    group = null
                    favoritesOnly = false
                    message = "已导入本地频道"
                }
            }, onFailure = { message = "无法导入文件，请选择 4 MB 以内的 M3U 文本" })
        }
    val selected = subscriptions.firstOrNull { it.id == selectedId } ?: subscriptions.firstOrNull()
    val channels =
        remember(selected, group, favoritesOnly, query) {
            selected?.channels.orEmpty().filter {
                (group == null || it.group == group) &&
                    (!favoritesOnly || it.id in selected!!.favorites) &&
                    (query.isBlank() || it.name.contains(query.trim(), true))
            }
        }
    GlassDialog(onDismiss = onDismiss, scrollable = false) {
        OverlayHeader(title = "IPTV · 电视直播", subtitle = "支持 HTTP / HTTPS 频道；刷新失败会保留旧列表", onClose = onDismiss)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 650.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ServerFormInput(label = "订阅名称", value = name, divider = true, enabled = !busy, onValueChange = {
                    name =
                        it
                })
                ServerFormInput(
                    label = "M3U 地址",
                    value = url,
                    divider = false,
                    enabled = !busy,
                    placeholder = "http:// 或 https://",
                    onValueChange = { url = it },
                )
                Row {
                    TextButton(enabled = !busy && name.isNotBlank() && url.isNotBlank(), onClick = {
                        perform {
                            selectedId = repository.add(name, url.trim())
                            group = null
                            favoritesOnly = false
                            name = ""
                            url = ""
                            message = "订阅已添加"
                        }
                    }) { Text(if (busy) "处理中…" else "添加订阅") }
                    TextButton(enabled = !busy, onClick = importFile) { Text("导入 M3U 文件") }
                }
            }
            message?.let { value -> item { Text(value, style = AppTypography.caption.regular, color = palette.sub2) } }
            items(subscriptions, key = { "subscription:${it.id}" }) { subscription ->
                Column {
                    OverlayActionRow(
                        label = (if (subscription.id == selected?.id) "✓ " else "") + subscription.name,
                        description =
                            "${subscription.channels.size} 个频道" +
                                if (subscription.skipped > 0) " · 跳过 ${subscription.skipped} 个无效或不支持项" else "",
                        onClick = {
                            selectedId = subscription.id
                            group = null
                            query = ""
                        },
                    )
                    Row {
                        if (subscription.sourceUrl !=
                            null
                        ) {
                            TextButton(enabled = !busy, onClick = {
                                perform {
                                    repository.refresh(subscription.id)
                                    message =
                                        "频道已更新"
                                }
                            }) { Text("刷新") }
                        }
                        TextButton(enabled = !busy, onClick = { removing = subscription }) { Text("移除") }
                    }
                }
            }
            if (selected != null) {
                item {
                    ServerFormInput(label = "搜索频道", value = query, divider = false, onValueChange = { query = it })
                    Row {
                        TextButton(
                            onClick = { favoritesOnly = !favoritesOnly },
                        ) { Text(if (favoritesOnly) "✓ 仅收藏" else "全部频道") }
                        TextButton(onClick = { group = null }) { Text(if (group == null) "✓ 所有分组" else "清除分组") }
                    }
                }
                items(selected.channels.map { it.group }.distinct(), key = { "group:$it" }) { label ->
                    TextButton(onClick = {
                        group =
                            if (group ==
                                label
                            ) {
                                null
                            } else {
                                label
                            }
                    }) { Text((if (group == label) "✓ " else "") + label) }
                }
                items(channels, key = { "channel:${it.id}" }) { channel ->
                    Row(Modifier.fillMaxWidth()) {
                        OverlayActionRow(
                            modifier =
                                Modifier.weight(
                                    1f,
                                ),
                            label = channel.name,
                            description = channel.group,
                            onClick = {
                                // Bound the player queue while keeping the tapped channel and its next neighbors.
                                val index = channels.indexOf(channel)
                                val queue = channels.drop(index).take(200)
                                start = 0
                                launch = queue.map { it.toPlayerItem(selected.id) }
                            },
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = { perform { repository.toggleFavorite(selected.id, channel.id) } },
                        ) {
                            Text(if (channel.id in selected.favorites) "★" else "☆")
                        }
                    }
                }
                if (channels.isEmpty()) item { Text("当前分组或筛选没有频道", color = palette.sub2) }
            }
        }
    }
    removing?.let { subscription ->
        ConfirmDialog(
            title = "移除订阅",
            message = "移除「${subscription.name}」及其收藏？",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = {
                removing = null
                perform { repository.remove(subscription.id) }
            },
            onDismiss = {
                removing =
                    null
            },
        )
    }
    launch?.let { items ->
        PlayerLauncher(items = items, startIndex = start, startPositionMs = 0L, onLaunched = {
            launch =
                null
        })
    }
}

internal fun IptvChannel.toPlayerItem(subscriptionId: String) =
    PlayerMediaItem(
        id = "external-iptv-$subscriptionId-$id",
        url = url,
        transcodeUrl = "",
        title = name,
        customHeaders = headers.takeIf { it.isNotEmpty() }?.let(::PlayerCustomHeaders),
        mediaType = "LiveTv",
    )
