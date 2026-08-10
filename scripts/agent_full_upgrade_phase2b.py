from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    write(path, text.replace(old, new, count))


if (ROOT / "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/DownloadsScreen.kt").exists():
    print("phase2b already applied")
    raise SystemExit(0)

# ---------------------------------------------------------------- Full download task manager UI, using the existing resumable engine.
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/DownloadsScreen.kt",
    r'''package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccent
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.OfflineMediaManager

enum class DownloadFilter(val label: String) {
    All("全部"), Active("进行中"), Completed("已完成"), Failed("失败")
}

enum class DownloadSort(val label: String) { Updated("最近更新"), Name("名称"), Size("大小") }

@Composable
internal fun DownloadsScreen(
    onBack: () -> Unit,
    manager: OfflineMediaManager,
    onPlay: (OfflineMedia) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    val items by manager.items.collectAsState()
    val wifiOnly by manager.wifiOnly.collectAsState()
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var sort by remember { mutableStateOf(DownloadSort.Updated) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val shown = remember(items, filter, sort) {
        items.filter { item ->
            when (filter) {
                DownloadFilter.All -> true
                DownloadFilter.Active -> item.status in setOf(
                    DownloadStatus.Queued,
                    DownloadStatus.WaitingForWifi,
                    DownloadStatus.Downloading,
                    DownloadStatus.Paused,
                )
                DownloadFilter.Completed -> item.status == DownloadStatus.Completed
                DownloadFilter.Failed -> item.status == DownloadStatus.Failed
            }
        }.let { values ->
            when (sort) {
                DownloadSort.Updated -> values.sortedByDescending { it.updatedAtEpochMs }
                DownloadSort.Name -> values.sortedBy { it.title.lowercase() }
                DownloadSort.Size -> values.sortedByDescending { maxOf(it.totalBytes, it.downloadedBytes) }
            }
        }
    }
    val selectedItems = items.filter { it.id in selected }
    val usedBytes = items.filter { it.status == DownloadStatus.Completed }.sumOf { it.downloadedBytes }
    val activeCount = items.count { it.status in setOf(DownloadStatus.Queued, DownloadStatus.WaitingForWifi, DownloadStatus.Downloading, DownloadStatus.Paused) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).pressable(onClick = onBack)
                        .glass(continuousRounded(12.dp), palette.card3, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.ChevronLeft, "返回", tint = palette.text, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("下载中心", style = sc(20f, 700), color = palette.text)
                    Text(
                        "${items.size} 项 · $activeCount 进行中 · ${formatDownloadBytes(usedBytes)} 离线文件",
                        style = mr(10.5f, 500),
                        color = palette.sub2,
                    )
                }
                Text(
                    if (selected.isEmpty()) "多选" else "完成",
                    style = sc(12f, 700),
                    color = accent,
                    modifier = Modifier.pressable {
                        selected = if (selected.isEmpty()) shown.mapTo(linkedSetOf()) { it.id } else emptySet()
                    }.padding(8.dp),
                )
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal)
                    .glass(GlassShapes.card, palette.card, palette.border)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("下载策略", style = sc(13f, 700), color = palette.text)
                    Text(sort.label, style = mr(11f, 600), color = accent, modifier = Modifier.pressable {
                        sort = DownloadSort.entries[(DownloadSort.entries.indexOf(sort) + 1) % DownloadSort.entries.size]
                    })
                }
                Row(
                    Modifier.fillMaxWidth().pressable { manager.setWifiOnly(!wifiOnly) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("仅 Wi-Fi 下载", style = sc(12.5f, 600), color = palette.text)
                        Text("关闭后可能使用蜂窝流量", style = mr(10.5f, 400), color = palette.sub2)
                    }
                    Text(if (wifiOnly) "已开启" else "已关闭", style = sc(11f, 700), color = if (wifiOnly) accent else palette.sub2)
                }
            }
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(DownloadFilter.entries) { value ->
                    val active = filter == value
                    Text(
                        value.label,
                        style = sc(11.5f, if (active) 700 else 500),
                        color = if (active) accent else palette.body,
                        modifier = Modifier.pressable { filter = value }
                            .glass(
                                GlassShapes.chip,
                                if (active) accent.copy(alpha = 0.13f) else palette.card2,
                                if (active) accent.copy(alpha = 0.28f) else palette.border,
                            ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (selectedItems.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal)
                        .glass(GlassShapes.card, palette.card2, palette.border)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BatchAction("暂停") { selectedItems.forEach { manager.pause(it.id) } }
                    BatchAction("继续/重试") { selectedItems.forEach { manager.resume(it.id) } }
                    BatchAction("删除", danger = true) {
                        selectedItems.forEach { manager.remove(it.id) }
                        selected = emptySet()
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(AppIcons.Download, null, tint = palette.hint, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (items.isEmpty()) "还没有下载任务\n在详情页选择下载后会出现在这里" else "当前筛选没有任务",
                        style = sc(12f, 400, lineHeight = 19f),
                        color = palette.hint,
                    )
                }
            }
        } else {
            items(shown, key = { it.id }) { item ->
                DownloadTaskRow(
                    item = item,
                    selected = item.id in selected,
                    selectionMode = selected.isNotEmpty(),
                    onToggleSelected = {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    },
                    onPlay = { onPlay(item) },
                    onPause = { manager.pause(item.id) },
                    onResume = { manager.resume(item.id) },
                    onRemove = { manager.remove(item.id); selected = selected - item.id },
                    modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
    }
}

@Composable
private fun BatchAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    Text(
        label,
        style = sc(11f, 700),
        color = if (danger) Semantic.Error else accent,
        modifier = Modifier.pressable(onClick = onClick)
            .glass(GlassShapes.chip, palette.card3, palette.border)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun DownloadTaskRow(
    item: OfflineMedia,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelected: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    Column(
        modifier.fillMaxWidth()
            .pressable(onClick = if (selectionMode) onToggleSelected else {
                { if (item.playable) onPlay() else if (item.status == DownloadStatus.Downloading) onPause() else onResume() }
            })
            .glass(
                GlassShapes.card,
                if (selected) accent.copy(alpha = 0.10f) else palette.card,
                if (selected) accent.copy(alpha = 0.30f) else palette.border,
            ).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Text(if (selected) "✓" else "○", style = sc(16f, 700), color = if (selected) accent else palette.sub2)
                Spacer(Modifier.size(9.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = sc(13f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    downloadStatusText(item),
                    style = mr(10.5f, 500),
                    color = if (item.status == DownloadStatus.Failed) Semantic.Error else palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selectionMode) {
                Text(
                    when (item.status) {
                        DownloadStatus.Completed -> "播放"
                        DownloadStatus.Downloading -> "暂停"
                        DownloadStatus.Failed -> "重试"
                        else -> "继续"
                    },
                    style = sc(11f, 700),
                    color = accent,
                )
                Icon(
                    AppIcons.Close,
                    "删除下载",
                    tint = palette.sub2,
                    modifier = Modifier.padding(start = 10.dp).size(28.dp).pressable(onClick = onRemove).padding(7.dp),
                )
            }
        }
        if (item.status != DownloadStatus.Completed) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(continuousRounded(2.dp)).background(palette.border)) {
                Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).height(4.dp).background(accent))
            }
        }
    }
}

private fun downloadStatusText(item: OfflineMedia): String = when (item.status) {
    DownloadStatus.Queued -> "等待下载"
    DownloadStatus.WaitingForWifi -> "等待 Wi-Fi"
    DownloadStatus.Downloading -> "${formatDownloadBytes(item.downloadedBytes)} / ${formatDownloadBytes(item.totalBytes)}"
    DownloadStatus.Paused -> "已暂停 · ${formatDownloadBytes(item.downloadedBytes)}"
    DownloadStatus.Completed -> "已完成 · ${formatDownloadBytes(item.downloadedBytes)}"
    DownloadStatus.Failed -> item.error ?: "下载失败，可点按重试"
}

internal fun formatDownloadBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "${(value / 1024.0 / 1024.0 / 1024.0 * 10).toInt() / 10.0} GB"
    value >= 1024L * 1024L -> "${value / 1024L / 1024L} MB"
    value >= 1024L -> "${value / 1024L} KB"
    else -> "$value B"
}
''',
)

# ---------------------------------------------------------------- Profile IA + split downloads/settings pages out of giant root.
profile = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt"
replace(
    profile,
    '''                        ProfilePage.Downloads,
                        ProfilePage.Recovery,
                        ProfilePage.Splash,
                        -> ProfileUtilityScreen(
                            page = activePage,
                            onBack = ::closePage,
                            offlineManager = component.offlineMedia,
                            onPlayOffline = { offlineToPlay = it },
                            syncManager = component.syncManager,
                            playbackRecovery = component.playbackRecovery,
                            themePreferences = prefs,
                            onResumePlayback = { snapshot ->
                                component.recoveryItem(snapshot)?.let { recoveryToPlay = it to snapshot }
                            },
                        )
''',
    '''                        ProfilePage.Downloads -> DownloadsScreen(
                            onBack = ::closePage,
                            manager = component.offlineMedia,
                            onPlay = { offlineToPlay = it },
                        )

                        ProfilePage.Recovery,
                        ProfilePage.Splash,
                        -> ProfileUtilityScreen(
                            page = activePage,
                            onBack = ::closePage,
                            syncManager = component.syncManager,
                            playbackRecovery = component.playbackRecovery,
                            themePreferences = prefs,
                            onResumePlayback = { snapshot ->
                                component.recoveryItem(snapshot)?.let { recoveryToPlay = it to snapshot }
                            },
                        )
''',
)
# Root IA: settings stay focused; downloads/recovery become "我的内容", low-frequency tools become Advanced.
text = read(profile)
text = text.replace('Section(title = "数据与应用")', 'Section(title = "我的内容")', 1)
text = text.replace('"数据与诊断",\n                                            "${state.servers.size} 台服务器 · 缓存与日志 ›",', '"高级设置",\n                                            "网络兼容 · 备份 · 缓存 · 诊断 ›",', 1)
write(profile, text)

# Playback no longer contains UA; Watch Together no longer contains custom endpoint.
replace(profile, '                            customUserAgent = if (customUserAgent.isBlank()) "应用默认 ›" else "已启用 ›",\n', '')
replace(profile, '                            onUserAgent = { sheet = Sheet.UserAgent },\n', '')
replace(profile, '                            customEndpoint = watchEndpoint.trimEnd(\'/\') !=\n                                WatchTogetherPreferences.DEFAULT_ENDPOINT.trimEnd(\'/\'),\n', '')
replace(profile, '                            onEndpoint = { sheet = Sheet.WatchEndpoint },\n', '')
# Advanced call gets low-frequency network tools.
replace(
    profile,
    '''                            DataAndDiagnosticsScreen(
                                onBack = ::closePage,
                                serverCount = state.servers.size,
                                backupPayload = backupPayload,
                                onImport = component::importServers,
                                onClearCache = { confirmClearCache = true },
                            )
''',
    '''                            DataAndDiagnosticsScreen(
                                onBack = ::closePage,
                                serverCount = state.servers.size,
                                backupPayload = backupPayload,
                                customUserAgent = customUserAgent,
                                watchEndpoint = watchEndpoint,
                                onImport = component::importServers,
                                onUserAgent = { sheet = Sheet.UserAgent },
                                onWatchEndpoint = { sheet = Sheet.WatchEndpoint },
                                onClearCache = { confirmClearCache = true },
                            )
''',
)
# Modify local settings signatures/content first, then extract them to another file.
replace(profile, '    skipSegments: String,\n    customUserAgent: String,\n', '    skipSegments: String,\n')
replace(profile, '    onSkipSegments: () -> Unit,\n    onUserAgent: () -> Unit,\n', '    onSkipSegments: () -> Unit,\n')
text = read(profile)
network_block = '''        item {
            Section(title = "网络与兼容") {
                SettingsCard {
                    SettingRow("自定义 User-Agent", customUserAgent, true, onUserAgent)
                }
            }
        }
'''
text = text.replace(network_block, '', 1)
write(profile, text)
replace(profile, '    chatPreview: Boolean,\n    customEndpoint: Boolean,\n', '    chatPreview: Boolean,\n')
replace(profile, '    onChatPreview: (Boolean) -> Unit,\n    onEndpoint: () -> Unit,\n', '    onChatPreview: (Boolean) -> Unit,\n')
text = read(profile)
connection_block = '''        item {
            Section(title = "连接") {
                SettingsCard {
                    SettingRow(
                        "一起看服务器",
                        if (customEndpoint) "自定义 ›" else "默认 ›",
                        true,
                        onEndpoint,
                    )
                }
            }
        }
'''
text = text.replace(connection_block, '', 1)
write(profile, text)
# Advanced screen signature/title/sections.
replace(profile, '    backupPayload: String,\n    onImport: (String) -> Result<Int>,\n    onClearCache: () -> Unit,\n', '    backupPayload: String,\n    customUserAgent: String,\n    watchEndpoint: String,\n    onImport: (String) -> Result<Int>,\n    onUserAgent: () -> Unit,\n    onWatchEndpoint: () -> Unit,\n    onClearCache: () -> Unit,\n')
replace(profile, '        title = "数据与诊断",\n        subtitle = "迁移、缓存与问题排查",\n', '        title = "高级设置",\n        subtitle = "网络兼容、迁移与问题排查",\n')
replace(
    profile,
    '''    ) {
        item {
            Box(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
''',
    '''    ) {
        item {
            Section(title = "网络与兼容") {
                SettingsCard {
                    SettingRow(
                        "自定义 User-Agent",
                        if (customUserAgent.isBlank()) "应用默认 ›" else "已启用 ›",
                        true,
                        onUserAgent,
                    )
                    SettingsDivider()
                    SettingRow(
                        "一起看服务地址",
                        if (watchEndpoint.trimEnd('/') == WatchTogetherPreferences.DEFAULT_ENDPOINT.trimEnd('/')) "默认 ›" else "自定义 ›",
                        true,
                        onWatchEndpoint,
                    )
                }
            }
        }
        item {
            Box(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
''',
    count=1,
)
# Shared helpers must be visible from extracted settings file.
for old, new in [
    ('private fun SettingsPage(', 'internal fun SettingsPage('),
    ('private fun SettingsCard(', 'internal fun SettingsCard('),
    ('private fun SwitchRow(', 'internal fun SwitchRow('),
    ('private fun SettingsDivider(', 'internal fun SettingsDivider('),
]:
    replace(profile, old, new, 1)

# Extract four ordinary settings screen functions from ProfileScreen.kt.
text = read(profile)
start = text.index('@Composable\nprivate fun PlaybackSettingsScreen(')
end = text.index('@Composable\nprivate fun DataAndDiagnosticsScreen(', start)
settings_block = text[start:end]
settings_block = settings_block.replace('@Composable\nprivate fun PlaybackSettingsScreen', '@Composable\ninternal fun PlaybackSettingsScreen')
settings_block = settings_block.replace('@Composable\nprivate fun DanmakuSettingsScreen', '@Composable\ninternal fun DanmakuSettingsScreen')
settings_block = settings_block.replace('@Composable\nprivate fun WatchTogetherSettingsScreen', '@Composable\ninternal fun WatchTogetherSettingsScreen')
settings_block = settings_block.replace('@Composable\nprivate fun AppearanceSettingsScreen', '@Composable\ninternal fun AppearanceSettingsScreen')
write(profile, text[:start] + text[end:])
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileSettingsScreens.kt",
    '''package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import com.yfuse.core.data.ThemeMode
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

''' + settings_block,
)

# Strip old inline download screen/row from ProfileUtilityScreen.
text = read(profile)
old_sig = '''private fun ProfileUtilityScreen(
    page: ProfilePage,
    onBack: () -> Unit,
    offlineManager: OfflineMediaManager,
    onPlayOffline: (OfflineMedia) -> Unit,
    syncManager: ServerSyncManager,
    playbackRecovery: PlaybackRecoveryStore,
    themePreferences: ThemePreferences,
    onResumePlayback: (PlaybackRecoverySnapshot) -> Unit,
) {
'''
new_sig = '''private fun ProfileUtilityScreen(
    page: ProfilePage,
    onBack: () -> Unit,
    syncManager: ServerSyncManager,
    playbackRecovery: PlaybackRecoveryStore,
    themePreferences: ThemePreferences,
    onResumePlayback: (PlaybackRecoverySnapshot) -> Unit,
) {
'''
if old_sig not in text:
    raise SystemExit("ProfileUtilityScreen signature anchor missing")
text = text.replace(old_sig, new_sig, 1)
marker_after_recovery = '''        return
    }
    val palette = LocalPalette.current
    val downloads by offlineManager.items.collectAsState()
'''
start = text.index(marker_after_recovery) + len('        return\n    }\n')
end = text.index('\n}\n\n@Composable\nprivate fun RecoveryCenterScreen(', start)
text = text[:start] + '    Unit\n' + text[end:]
# Remove obsolete OfflineDownloadRow + formatter.
row_start = text.find('@Composable\nprivate fun OfflineDownloadRow(')
if row_start >= 0:
    row_end = text.index('/**\n * `width:38px', row_start)
    text = text[:row_start] + text[row_end:]
write(profile, text)

# ---------------------------------------------------------------- Home next-up shelf aggregated across all servers.
home_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeStore.kt"
replace(home_store, '    val resume: List<HomeResumeEntry> = emptyList(),\n', '    val resume: List<HomeResumeEntry> = emptyList(),\n    val nextUp: List<HomeResumeEntry> = emptyList(),\n')
replace(home_store, '    data class ResumeLoaded(val items: List<HomeResumeEntry>) : Msg\n', '    data class ResumeLoaded(val items: List<HomeResumeEntry>) : Msg\n    data class NextUpLoaded(val items: List<HomeResumeEntry>) : Msg\n')
replace(home_store, '        private var resumeJob: Job? = null\n', '        private var resumeJob: Job? = null\n        private var nextUpJob: Job? = null\n')
replace(home_store, '                    loadResume(action.servers)\n', '                    loadResume(action.servers)\n                    loadNextUp(action.servers)\n')
replace(home_store, '                    loadResume(registry.data.value.servers, force = true)\n', '                    loadResume(registry.data.value.servers, force = true)\n                    loadNextUp(registry.data.value.servers)\n', count=2)
# Insert loader before ownsResumeLoad.
replace(
    home_store,
    '        private fun ownsResumeLoad(\n',
    '''        private fun loadNextUp(servers: List<SavedServer>) {
            nextUpJob?.cancel()
            val available = servers.filter { it.knownUnavailableEndpointReason() == null }
            if (available.isEmpty()) {
                dispatch(Msg.NextUpLoaded(emptyList()))
                return
            }
            nextUpJob = scope.launch {
                val entries = coroutineScope {
                    available.map { server -> async {
                        emby.nextUpEpisodes(server, 8).getOrDefault(emptyList())
                            .map { HomeResumeEntry(it, server) }
                    } }.awaitAll().flatten()
                }
                dispatch(Msg.NextUpLoaded(entries.distinctBy { it.server.id to it.item.id }))
            }
        }

        private fun ownsResumeLoad(
''',
)
replace(home_store, '            is Msg.ResumeLoaded -> copy(resume = msg.items)\n', '            is Msg.ResumeLoaded -> copy(resume = msg.items)\n            is Msg.NextUpLoaded -> copy(nextUp = msg.items)\n')

home_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt"
replace(
    home_screen,
    '''                if (state.resume.isNotEmpty()) {
                    item {
                        ContinueWatching(
''',
    '''                if (state.nextUp.isNotEmpty()) {
                    item(key = "next-up") {
                        NextUpShelf(
                            items = state.nextUp,
                            onSeeAll = component.onOpenCalendar,
                            onClick = { component.store.accept(HomeIntent.OpenResume(it)) },
                        )
                    }
                }

                if (state.resume.isNotEmpty()) {
                    item {
                        ContinueWatching(
''',
)
# Add NextUp shelf by adapting ContinueWatching geometry.
insert_marker = '/** 继续观看 — 150×90 artwork with title/year below and a 3px progress bar. */\n'
next_up_fn = r'''@Composable
private fun NextUpShelf(
    items: List<HomeResumeEntry>,
    onSeeAll: () -> Unit,
    onClick: (HomeResumeEntry) -> Unit,
) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("下一集", style = Type.section(16f), color = palette.text)
                Text("继续追你正在看的剧集", style = mr(10.5f, 400), color = palette.sub2)
            }
            Text("追剧中心 ›", style = mr(11f, 600), color = LocalAccent.current.color, modifier = Modifier.pressable(onClick = onSeeAll))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "next-${it.server.id}-${it.item.id}" }) { entry ->
                val item = entry.item
                CaptionedPoster(
                    url = EmbyImages.primary(entry.server.baseUrl, item.posterItemId, item.posterTag, accessToken = entry.server.accessToken),
                    fallbackUrls = emptyList(),
                    title = item.title,
                    year = item.subtitle ?: item.year?.toString(),
                    sharedKey = null,
                    onClick = { onClick(entry) },
                    modifier = Modifier.width(MediaSizing.landscapeCardWidth),
                    posterModifier = Modifier.fillMaxWidth().height(MediaSizing.landscapeCardHeight),
                )
            }
        }
    }
}

'''
replace(home_screen, insert_marker, next_up_fn + insert_marker)

# ---------------------------------------------------------------- Calendar becomes a tracking center with Today/Upcoming/Unwatched.
calendar_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarStore.kt"
replace(
    calendar_store,
    '''enum class CalendarFilter(val label: String) {
    All("全部"),
    Mine("我的"),
    Domestic("国产"),
    Foreign("国外"),
''',
    '''enum class CalendarFilter(val label: String) {
    Today("今天"),
    Upcoming("即将更新"),
    Unwatched("待观看"),
    Mine("正在追"),
    All("全部"),
    Domestic("国产"),
    Foreign("国外"),
''',
)
replace(
    calendar_store,
    '''    fun accepts(entry: CalendarEntry): Boolean = when (this) {
        All -> true
        Mine -> entry.inLibrary
        Domestic -> entry.episode.origin == ShowOrigin.Domestic
        Foreign -> entry.episode.origin == ShowOrigin.Foreign
    }
''',
    '''    fun accepts(entry: CalendarEntry): Boolean = when (this) {
        Today, Upcoming, All -> true
        Unwatched -> entry.inLibrary && entry.status in setOf(
            com.yfuse.core.model.LibraryStatus.Available,
            com.yfuse.core.model.LibraryStatus.Missing,
        )
        Mine -> entry.inLibrary
        Domestic -> entry.episode.origin == ShowOrigin.Domestic
        Foreign -> entry.episode.origin == ShowOrigin.Foreign
    }
''',
)
replace(calendar_store, '    val filter: CalendarFilter = CalendarFilter.All,\n', '    val filter: CalendarFilter = CalendarFilter.Today,\n')
# visibleDays needs date-level filters.
old_visible = '''    val visibleDays: List<CalendarDay>
        get() = if (filter == CalendarFilter.All) {
            days
        } else {
            days.mapNotNull { day ->
                day.entries.filter(filter::accepts)
                    .takeIf { it.isNotEmpty() }
                    ?.let { day.copy(entries = it) }
            }
        }
'''
new_visible = '''    val visibleDays: List<CalendarDay>
        get() = days.mapNotNull { day ->
            val dateAccepted = when (filter) {
                CalendarFilter.Today -> day.date == today
                CalendarFilter.Upcoming -> day.date >= today
                else -> true
            }
            if (!dateAccepted) return@mapNotNull null
            day.entries.filter(filter::accepts).takeIf { it.isNotEmpty() }?.let { day.copy(entries = it) }
        }
'''
replace(calendar_store, old_visible, new_visible)

calendar_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarScreen.kt"
# Update title/subtitle only; existing filter row picks up enum values automatically.
text = read(calendar_screen)
text = text.replace('"追剧日历"', '"追剧中心"', 1)
text = text.replace('"最近 7 天 · 未来 21 天"', '"下一集 · 更新日历 · 待观看"', 1)
write(calendar_screen, text)

# ---------------------------------------------------------------- Add server UX: HTTP warning, password reveal, keyboard flow, scan metadata.
lan = "composeApp/src/commonMain/kotlin/com/yfuse/core/network/LanDiscovery.kt"
replace(lan, '    val id: String,\n)', '    val id: String,\n    val version: String? = null,\n)')
lan_android = "composeApp/src/androidMain/kotlin/com/yfuse/core/network/LanDiscovery.android.kt"
replace(lan_android, '    val Name: String,\n)', '    val Name: String,\n    val Version: String? = null,\n)')
replace(lan_android, '                        id = response.Id,\n', '                        id = response.Id,\n                        version = response.Version,\n')

servers_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersScreen.kt"
# Imports.
replace(servers_screen, 'import androidx.compose.foundation.text.KeyboardOptions\n', 'import androidx.compose.foundation.text.KeyboardActions\nimport androidx.compose.foundation.text.KeyboardOptions\n')
replace(servers_screen, 'import androidx.compose.ui.Alignment\n', 'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.focus.FocusDirection\n')
replace(servers_screen, 'import androidx.compose.ui.graphics.SolidColor\n', 'import androidx.compose.ui.graphics.SolidColor\nimport androidx.compose.ui.platform.LocalFocusManager\n')
replace(servers_screen, 'import androidx.compose.ui.text.input.KeyboardType\n', 'import androidx.compose.ui.text.input.ImeAction\nimport androidx.compose.ui.text.input.KeyboardType\nimport androidx.compose.ui.text.input.VisualTransformation\n')
# Manual HTTP warning and actionable error card.
replace(
    servers_screen,
    '''                if (form.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(form.error, style = mr(11f, 500), color = Brand.Danger)
                }
''',
    '''                if (!form.https) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.fillMaxWidth().glass(
                            continuousRounded(14.dp),
                            Color(0xFFFFA24A).copy(alpha = 0.11f),
                            Color(0xFFFFA24A).copy(alpha = 0.30f),
                        ).padding(12.dp),
                    ) {
                        Text("⚠ HTTP 连接未加密", style = sc(12f, 700), color = Color(0xFFD77922))
                        Text("仅建议在可信局域网使用；公网服务器优先使用 HTTPS。", style = sc(10.5f, 400), color = palette.sub)
                    }
                }
                if (form.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth().glass(
                            continuousRounded(14.dp),
                            Brand.Danger.copy(alpha = 0.08f),
                            Brand.Danger.copy(alpha = 0.26f),
                        ).padding(12.dp),
                    ) {
                        Text("连接失败", style = sc(12f, 700), color = Brand.Danger)
                        Text("${form.error}。请检查地址、端口、协议和账号后重试。", style = sc(10.5f, 400), color = palette.sub)
                    }
                }
''',
    count=1,
)
# Discovery metadata.
replace(
    servers_screen,
    '                                Text(server.address, style = mr(10.5f, 400), color = palette.sub2)\n',
    '''                                Text(
                                    listOfNotNull(
                                        server.address,
                                        server.version?.let { "Emby $it" },
                                        when {
                                            server.address.startsWith("https://", ignoreCase = true) -> "HTTPS"
                                            server.address.startsWith("http://", ignoreCase = true) -> "HTTP"
                                            else -> "局域网"
                                        },
                                    ).joinToString(" · "),
                                    style = mr(10.5f, 400),
                                    color = palette.sub2,
                                )
''',
)
# Password reveal + IME in OnboardInput.
replace(servers_screen, '    val palette = LocalPalette.current\n    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {\n', '    val palette = LocalPalette.current\n    val focusManager = LocalFocusManager.current\n    var revealPassword by rememberSaveable { mutableStateOf(false) }\n    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {\n', count=1)
replace(
    servers_screen,
    '''            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = mr(13f, 400).copy(color = palette.text),
                cursorBrush = SolidColor(Brand.Primary),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
''',
    '''            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = mr(13f, 400).copy(color = palette.text),
                    cursorBrush = SolidColor(Brand.Primary),
                    visualTransformation = if (password && !revealPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.weight(1f),
                )
                if (password) {
                    Text(
                        if (revealPassword) "隐藏" else "显示",
                        style = sc(10.5f, 600),
                        color = Brand.Primary,
                        modifier = Modifier.pressable { revealPassword = !revealPassword }.padding(start = 8.dp),
                    )
                }
            }
''',
    count=1,
)
# FormInput gets reveal and keyboard actions.
replace(servers_screen, '    val palette = LocalPalette.current\n    FormField(label = label, divider = divider, labelBottomPadding = 3.dp) {\n', '    val palette = LocalPalette.current\n    val focusManager = LocalFocusManager.current\n    var revealPassword by rememberSaveable { mutableStateOf(false) }\n    FormField(label = label, divider = divider, labelBottomPadding = 3.dp) {\n', count=1)
replace(
    servers_screen,
    '''            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = mr(13f, 500).copy(color = palette.text),
                cursorBrush = SolidColor(Brand.Primary),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
''',
    '''            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = mr(13f, 500).copy(color = palette.text),
                    cursorBrush = SolidColor(Brand.Primary),
                    visualTransformation = if (password && !revealPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = if (password) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (password) {
                    Text(
                        if (revealPassword) "隐藏" else "显示",
                        style = sc(10.5f, 600),
                        color = Brand.Primary,
                        modifier = Modifier.pressable { revealPassword = !revealPassword }.padding(start = 8.dp),
                    )
                }
            }
''',
    count=1,
)

print("phase2b patch applied")
