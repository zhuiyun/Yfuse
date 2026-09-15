package com.yfuse.feature.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.DEFAULT_PERSONAL_PROFILE
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.PersonalProfile
import com.yfuse.core.personal.importServerCollections
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.playback.PlaybackSyncManager
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeText as Text

enum class PersonalCenterTab(
    val label: String,
) {
    WatchLater("想看"),
    Favorites("收藏"),
    History("历史"),
    Profiles("家庭资料"),
    Sync("同步状态"),
}

@Composable
fun PersonalCenterScreen(
    personal: PersonalLibraryRepository,
    account: AccountRepository,
    playbackSync: PlaybackSyncManager,
    serverSync: ServerSyncManager,
    servers: List<SavedServer>,
    onBack: () -> Unit,
    onOpenMedia: (PersonalMediaRef) -> Unit,
    initialTab: PersonalCenterTab = PersonalCenterTab.WatchLater,
    repo: EmbyRepository? = null,
) {
    val state by personal.state.collectAsState()
    val accountState by account.state.collectAsState()
    val playbackState by playbackSync.state.collectAsState()
    val serverState by serverSync.state.collectAsState()
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PersonalProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf<PersonalProfile?>(null) }
    var showPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PersonalButton("返回", onBack)
                Column {
                    Text("个人中心", style = AppTypography.section.strong, color = palette.text)
                    Text(
                        "${state.activeProfile.name}${if (state.activeProfile.child) " · 儿童资料" else ""}",
                        color = palette.sub,
                    )
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalCenterTab.entries.forEach { target ->
                    PersonalButton(
                        if (target ==
                            tab
                        ) {
                            "● ${target.label}"
                        } else {
                            target.label
                        },
                        { tab = target },
                    )
                }
            }
        }
        message?.let { notice -> item { Text(notice, color = palette.sub) } }
        state.error?.let { notice -> item { Text(notice, color = palette.error) } }
        when (tab) {
            PersonalCenterTab.Profiles -> {
                item { Text("每份资料拥有独立的想看、收藏、历史和追剧。关联的服务器用户仍受服务器原有权限约束。", color = palette.sub) }
                item { Text("两份资料若关联同一个媒体服务器用户，服务器上的已看状态仍由该用户共享；需要服务端也独立时，请关联不同的服务器用户。", color = palette.sub) }
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PersonalButton("新建", {
                            editing = null
                            showEditor = true
                        }, enabled = !state.activeProfile.child)
                        PersonalButton(
                            if (state.hasGuardianPin) "修改家长 PIN" else "设置家长 PIN",
                            { showPin = true },
                            enabled = !busy,
                        )
                    }
                }
                items(state.profiles, key = { it.id }) { profile ->
                    PersonalCard {
                        Text(
                            "${profile.name}${if (profile.child) " · 儿童" else " · 成人"}",
                            style = AppTypography.body.strong,
                            color = palette.text,
                        )
                        Text(
                            if (profile.serverIds.isEmpty()) {
                                if (profile.child) "尚未关联服务器用户，当前不可浏览或播放" else "所有已登录的服务器用户"
                            } else {
                                "已关联 ${profile.serverIds.size} 个服务器用户"
                            },
                            color = palette.sub,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PersonalButton(if (profile.id == state.activeProfile.id) "正在使用" else "切换", {
                                if (state.activeProfile.child) {
                                    switching = profile
                                } else {
                                    scope.launch {
                                        personal.switchProfile(profile.id).onFailure { message = it.message }
                                    }
                                }
                            }, enabled = profile.id != state.activeProfile.id && !busy)
                            PersonalButton("编辑", {
                                editing = profile
                                showEditor = true
                            }, enabled = !state.activeProfile.child)
                            if (profile.id != DEFAULT_PERSONAL_PROFILE && profile.id != state.activeProfile.id) {
                                PersonalButton("移除资料", {
                                    scope.launch {
                                        personal.deleteProfile(profile.id).onFailure {
                                            message =
                                                it.message
                                        }
                                    }
                                }, enabled = !state.activeProfile.child)
                            }
                        }
                    }
                }
            }
            PersonalCenterTab.Sync -> {
                item {
                    PersonalCard {
                        Text("个人清单、历史与追剧", style = AppTypography.body.strong, color = palette.text)
                        Text(if (state.pendingSync) "有本机更改待同步" else "本机更改已同步", color = palette.sub)
                        state.lastSyncedAtEpochMs?.let {
                            Text("最近成功：${java.time.Instant.ofEpochMilli(it)}", color = palette.sub)
                        }
                        Text("加密后合并两台设备的记录；已删除的项目不会被旧备份恢复。服务器配置不会被此操作替换。", color = palette.sub)
                        state.error?.let { Text(it, color = palette.error) }
                        PersonalButton(if (state.syncing) "正在合并…" else "合并同步 / 重试", {
                            scope.launch {
                                account.syncPersonalNow().onSuccess { message = "个人数据已同步" }.onFailure {
                                    message =
                                        it.message
                                }
                            }
                        }, enabled = accountState is AccountState.SignedIn && !state.syncing)
                        if (accountState !is AccountState.SignedIn) {
                            Text(
                                "请先登录 Yfuse 账号；本机个人数据仍可使用。",
                                color = palette.sub,
                            )
                        }
                    }
                }
                item {
                    PersonalCard {
                        Text("播放进度", style = AppTypography.body.strong, color = palette.text)
                        Text(
                            "待上传 ${playbackState.pendingCount} 项 · ${if (playbackState.syncing) "正在同步" else "空闲"}",
                            color = palette.sub,
                        )
                        Text(
                            playbackState.lastSyncedAtEpochMs?.let { "最近成功：${java.time.Instant.ofEpochMilli(it)}" }
                                ?: "尚无成功同步记录",
                            color = palette.sub,
                        )
                        playbackState.error?.let { Text(it, color = palette.error) }
                        PersonalButton(
                            "拉取最新进度并重试",
                            playbackSync::refreshNow,
                            enabled =
                                !playbackState.syncing && accountState is AccountState.SignedIn,
                        )
                    }
                }
                item {
                    PersonalCard {
                        Text("媒体服务器状态", style = AppTypography.body.strong, color = palette.text)
                        Text(
                            "待处理 ${serverState.pendingCount} 项 · 冲突 ${serverState.conflicts.size} 项",
                            color = palette.sub,
                        )
                        PersonalButton("重试服务器同步", { scope.launch { serverSync.syncAll(force = true) } })
                        serverState.statuses.filter { personal.canAccessServer(it.serverId) }.forEach { status ->
                            Text(
                                "${status.serverName}：${status.error ?: if (status.syncing) "同步中" else "就绪"}",
                                color =
                                    if (status.error ==
                                        null
                                    ) {
                                        palette.sub
                                    } else {
                                        palette.error
                                    },
                            )
                        }
                    }
                }
                items(serverState.conflicts.filter { personal.canAccessServer(it.mutation.serverId) }) { conflict ->
                    PersonalCard {
                        val kindLabel = if (conflict.mutation.kind.name == "Favorite") "收藏" else "已看"
                        Text(
                            "${conflict.mutation.title} · ${kindLabel}冲突",
                            color = palette.text,
                        )
                        Text("本机：${conflict.mutation.desired}；服务器：${conflict.serverValue}", color = palette.sub)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PersonalButton("保留本机", {
                                scope.launch {
                                    serverSync.resolveConflict(conflict, true).onFailure {
                                        message =
                                            it.message
                                    }
                                }
                            })
                            PersonalButton("采用服务器", {
                                scope.launch {
                                    serverSync.resolveConflict(conflict, false).onFailure {
                                        message =
                                            it.message
                                    }
                                }
                            })
                        }
                    }
                }
            }
            else -> {
                item { YfFormField(value = query, onValueChange = { query = it }, label = "搜索当前资料") }
                if (tab != PersonalCenterTab.History && repo != null) {
                    item {
                        PersonalButton(if (busy) "正在导入…" else "导入 Emby / Jellyfin 清单", {
                            scope.launch {
                                busy = true
                                try {
                                    personal
                                        .importServerCollections(repo, servers)
                                        .onSuccess {
                                            message =
                                                "已导入 $it 项，现有个人选择已保留"
                                        }.onFailure { message = it.message }
                                } finally {
                                    busy = false
                                }
                            }
                        }, enabled = !busy)
                    }
                }
                val entries =
                    when (tab) {
                        PersonalCenterTab.Favorites -> state.favorites
                        PersonalCenterTab.History -> state.history
                        else -> state.watchLater
                    }
                val visible = entries.filter { it.media.title.contains(query.trim(), ignoreCase = true) }
                if (visible.isEmpty()) {
                    item {
                        Text(if (query.isBlank()) "这里还没有记录。可从作品详情加入个人想看或收藏。" else "没有匹配的记录", color = palette.sub)
                    }
                }
                items(visible, key = { it.identity }) { entry ->
                    PersonalEntryCard(entry, onOpen = { onOpenMedia(entry.media) }, onRemove = {
                        runCatching {
                            when (entry.collection) {
                                PersonalCollection.Favorite -> personal.setFavorite(entry.media, false)
                                PersonalCollection.WatchLater -> personal.setWatchLater(entry.media, false)
                                PersonalCollection.History -> personal.removeHistory(entry.media)
                            }
                        }.onFailure { message = it.message }
                    })
                }
            }
        }
    }
    if (showEditor) {
        PersonalProfileEditor(editing, servers, { showEditor = false }) { name, child, ids ->
            busy = true
            scope.launch {
                try {
                    personal.saveProfile(editing?.id, name, child, ids).onSuccess { showEditor = false }.onFailure {
                        message =
                            it.message
                    }
                } finally {
                    busy = false
                }
            }
        }
    }
    switching?.let { profile ->
        PersonalPinDialog("切换到 ${profile.name}", false, { switching = null }) { pin, _ ->
            scope.launch {
                personal.switchProfile(profile.id, pin.toCharArray()).onSuccess { switching = null }.onFailure {
                    message =
                        it.message
                }
            }
        }
    }
    if (showPin) {
        PersonalPinDialog("家长 PIN", true, { showPin = false }) { old, next ->
            scope.launch {
                personal
                    .setGuardianPin(next.toCharArray(), old.toCharArray())
                    .onSuccess {
                        showPin = false
                        message =
                            "家长 PIN 已保存"
                    }.onFailure { message = it.message }
            }
        }
    }
}

@Composable
private fun PersonalCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(LocalPalette.current.card2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun PersonalEntryCard(
    entry: PersonalEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    PersonalCard {
        Text(entry.media.title, style = AppTypography.body.strong, color = LocalPalette.current.text)
        Text(
            listOfNotNull(
                entry.media.year?.toString(),
                if (entry.collection == PersonalCollection.History) {
                    if (entry.completed) "已看完" else "看到 ${entry.positionMs / 60_000} 分钟"
                } else {
                    null
                },
            ).joinToString(" · "),
            color = LocalPalette.current.sub,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonalButton("查看作品", onOpen)
            PersonalButton("移除", onRemove)
        }
    }
}

@Composable
private fun PersonalProfileEditor(
    profile: PersonalProfile?,
    servers: List<SavedServer>,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Set<String>) -> Unit,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var child by remember(profile?.id) { mutableStateOf(profile?.child ?: false) }
    var ids by remember(profile?.id) { mutableStateOf(profile?.serverIds.orEmpty()) }
    GlassDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (profile ==
                    null
                ) {
                    "新建家庭资料"
                } else {
                    "编辑家庭资料"
                },
                style = AppTypography.section.strong,
                color = LocalPalette.current.text,
            )
            YfFormField(name, { name = it }, label = "资料名称")
            PersonalButton(
                if (child) "● 儿童资料" else "成人资料",
                { child = !child },
                enabled =
                    profile?.id != DEFAULT_PERSONAL_PROFILE,
            )
            Text("选择关联的服务器用户。儿童资料需要家长 PIN，并且只能使用勾选的用户权限。", color = LocalPalette.current.sub)
            Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                servers.forEach { server ->
                    PersonalButton("${if (server.id in ids) "✓ " else ""}${server.serverName} · ${server.userName}", {
                        ids = if (server.id in ids) ids - server.id else ids + server.id
                    })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalButton("保存", { onSave(name, child, ids) })
                PersonalButton("取消", onDismiss)
            }
        }
    }
}

@Composable
private fun PersonalPinDialog(
    title: String,
    setting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    GlassDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = AppTypography.section.strong, color = LocalPalette.current.text)
            YfFormField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                label = if (setting) "当前 PIN（首次设置留空）" else "家长 PIN",
                visualTransformation = PasswordVisualTransformation(),
            )
            if (setting) {
                YfFormField(next, {
                    next = it.filter(Char::isDigit).take(12)
                }, label = "新 PIN（4–12 位）", visualTransformation = PasswordVisualTransformation())
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalButton("确认", { onSubmit(pin, next) })
                PersonalButton("取消", onDismiss)
            }
        }
    }
}

@Composable
private fun PersonalButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    YfButton(label, onClick, modifier = Modifier.width(156.dp), enabled = enabled)
}
