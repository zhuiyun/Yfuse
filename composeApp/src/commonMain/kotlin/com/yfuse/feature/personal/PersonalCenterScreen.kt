package com.yfuse.feature.personal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
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
import com.yfuse.feature.profile.Section
import com.yfuse.feature.profile.SettingRow
import com.yfuse.feature.profile.SettingSegmentRow
import com.yfuse.feature.profile.SettingsCard
import com.yfuse.feature.profile.SettingsDivider
import com.yfuse.feature.profile.SettingsPage
import com.yfuse.feature.profile.SwitchRow
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
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PersonalProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf<PersonalProfile?>(null) }
    var showPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val contentTabs = listOf(PersonalCenterTab.WatchLater, PersonalCenterTab.Favorites, PersonalCenterTab.History)
    val contentPage = initialTab in contentTabs
    SettingsPage(
        title = if (contentPage) "我的内容" else initialTab.label,
        subtitle = state.activeProfile.name,
        onBack = onBack,
    ) {
        if (contentPage) {
            item {
                Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                    SettingsCard {
                        SettingSegmentRow(
                            title = "内容分类",
                            options = listOf("想看", "收藏", "观看历史"),
                            selectedIndex = contentTabs.indexOf(tab).coerceAtLeast(0),
                            onSelect = { tab = contentTabs[it] },
                        )
                    }
                }
            }
        }
        message?.let { notice -> item { PersonalNotice(notice) } }
        state.error?.let { notice -> item { PersonalNotice(notice, error = true) } }
        when (tab) {
            PersonalCenterTab.Profiles -> {
                item { PersonalNotice("每份资料分别保存想看、收藏、观看历史和追剧。") }
                item { PersonalNotice("需要服务器观看记录也独立时，请为家庭成员关联不同的服务器用户。") }
                item {
                    Section(title = "管理资料") {
                        SettingsCard {
                            SettingRow(
                                "新建家庭资料",
                                if (state.activeProfile.child) "请切换至成人资料" else "添加家庭成员 ›",
                                embedded = true,
                                icon = AppIcons.User,
                                onClick =
                                    if (state.activeProfile.child ||
                                        busy
                                    ) {
                                        null
                                    } else {
                                        (
                                            {
                                                editing = null
                                                showEditor = true
                                            }
                                        )
                                    },
                            )
                            SettingsDivider()
                            SettingRow(
                                if (state.hasGuardianPin) "修改家长 PIN" else "设置家长 PIN",
                                "儿童资料与切换保护 ›",
                                embedded = true,
                                onClick =
                                    if (busy) {
                                        null
                                    } else {
                                        (
                                            {
                                                showPin =
                                                    true
                                            }
                                        )
                                    },
                            )
                        }
                    }
                }
                items(state.profiles, key = { it.id }) { profile ->
                    Section(title = profile.name + if (profile.child) " · 儿童" else " · 成人") {
                        SettingsCard {
                            SettingRow(
                                "关联服务器",
                                if (profile.serverIds.isEmpty()) {
                                    if (profile.child) "尚未关联，当前不可浏览或播放" else "所有已登录的服务器用户"
                                } else {
                                    "已关联 " + profile.serverIds.size + " 个服务器用户"
                                },
                                embedded = true,
                            )
                            SettingsDivider()
                            SettingRow(
                                "当前资料",
                                if (profile.id ==
                                    state.activeProfile.id
                                ) {
                                    "正在使用"
                                } else {
                                    "切换到此资料 ›"
                                },
                                embedded = true,
                                onClick =
                                    if (profile.id ==
                                        state.activeProfile.id ||
                                        busy
                                    ) {
                                        null
                                    } else {
                                        (
                                            {
                                                if (state.activeProfile.child) {
                                                    switching = profile
                                                } else {
                                                    scope.launch {
                                                        personal.switchProfile(profile.id).onFailure {
                                                            message =
                                                                it.message
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    },
                            )
                            if (!state.activeProfile.child) {
                                SettingsDivider()
                                SettingRow("编辑资料", "名称、类型与服务器权限 ›", embedded = true, onClick = {
                                    editing = profile
                                    showEditor =
                                        true
                                })
                                if (profile.id != DEFAULT_PERSONAL_PROFILE && profile.id != state.activeProfile.id) {
                                    SettingsDivider()
                                    SettingRow("移除资料", "移除此家庭成员", embedded = true, onClick = {
                                        scope.launch {
                                            personal.deleteProfile(profile.id).onFailure {
                                                message =
                                                    it.message
                                            }
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
            PersonalCenterTab.Sync -> {
                item {
                    Section(title = "个人内容") {
                        SettingsCard {
                            SettingRow(
                                "清单、历史与追剧",
                                when {
                                    state.syncing -> "正在同步…"
                                    state.pendingSync -> "有本机更改待同步"
                                    else -> "本机更改已同步"
                                },
                                embedded = true,
                            )
                            SettingsDivider()
                            SettingRow(
                                "最近成功",
                                state.lastSyncedAtEpochMs?.let {
                                    java.time.Instant
                                        .ofEpochMilli(it)
                                        .toString()
                                }
                                    ?: "暂无同步记录",
                                embedded = true,
                            )
                            SettingsDivider()
                            SettingRow(
                                "立即同步",
                                if (accountState !is AccountState.SignedIn) "请先登录鱼服账号" else "合并个人数据并重试 ›",
                                embedded = true,
                                icon = AppIcons.Refresh,
                                onClick =
                                    if (accountState !is AccountState.SignedIn ||
                                        state.syncing
                                    ) {
                                        null
                                    } else {
                                        (
                                            {
                                                scope.launch {
                                                    account
                                                        .syncPersonalNow()
                                                        .onSuccess { message = "个人数据已同步" }
                                                        .onFailure {
                                                            message =
                                                                it.message
                                                        }
                                                }
                                            }
                                        )
                                    },
                            )
                        }
                    }
                }
                item { PersonalNotice("个人数据加密合并，保留删除记录；服务器配置与设置备份仍需手动操作。") }
                item {
                    Section(title = "播放进度") {
                        SettingsCard {
                            SettingRow(
                                "同步状态",
                                "待上传 " + playbackState.pendingCount + " 项 · " +
                                    if (playbackState.syncing) "同步中" else "空闲",
                                embedded = true,
                            )
                            SettingsDivider()
                            SettingRow(
                                "最近成功",
                                playbackState.lastSyncedAtEpochMs?.let {
                                    java.time.Instant
                                        .ofEpochMilli(it)
                                        .toString()
                                }
                                    ?: "暂无同步记录",
                                embedded = true,
                            )
                            SettingsDivider()
                            SettingRow(
                                "刷新与重试",
                                "拉取最新播放进度 ›",
                                embedded = true,
                                icon = AppIcons.Refresh,
                                onClick =
                                    if (playbackState.syncing ||
                                        accountState !is AccountState.SignedIn
                                    ) {
                                        null
                                    } else {
                                        playbackSync::refreshNow
                                    },
                            )
                        }
                    }
                }
                playbackState.error?.let { error -> item { PersonalNotice(error, error = true) } }
                item {
                    Section(title = "媒体服务器") {
                        SettingsCard {
                            SettingRow(
                                "同步状态",
                                "待处理 " + serverState.pendingCount + " 项 · 冲突 " + serverState.conflicts.size + " 项",
                                embedded = true,
                            )
                            serverState.statuses.filter { personal.canAccessServer(it.serverId) }.forEach { status ->
                                SettingsDivider()
                                SettingRow(
                                    status.serverName,
                                    status.error ?: if (status.syncing) "同步中" else "就绪",
                                    embedded = true,
                                )
                            }
                            SettingsDivider()
                            SettingRow("重试服务器同步", "重新提交待处理更改 ›", embedded = true, icon = AppIcons.Refresh, onClick = {
                                scope.launch { serverSync.syncAll(force = true) }
                            })
                        }
                    }
                }
                items(serverState.conflicts.filter { personal.canAccessServer(it.mutation.serverId) }) { conflict ->
                    Section(title = conflict.mutation.title) {
                        SettingsCard {
                            val kind = if (conflict.mutation.kind.name == "Favorite") "收藏" else "已看"
                            SettingRow(
                                kind + "冲突",
                                "本机：" + conflict.mutation.desired + " · 服务器：" + conflict.serverValue,
                                embedded = true,
                            )
                            SettingsDivider()
                            SettingRow("保留本机", "使用当前资料的选择", embedded = true, onClick = {
                                scope.launch {
                                    serverSync.resolveConflict(conflict, true).onFailure {
                                        message =
                                            it.message
                                    }
                                }
                            })
                            SettingsDivider()
                            SettingRow("采用服务器", "使用服务器的选择", embedded = true, onClick = {
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
                item {
                    YfFormField(value = query, onValueChange = {
                        query = it
                    }, label = "搜索当前资料", modifier = Modifier.padding(horizontal = Dimens.pageHorizontal))
                }
                if (tab != PersonalCenterTab.History && repo != null) {
                    item {
                        Section(title = "导入清单") {
                            SettingsCard {
                                SettingRow(
                                    "从媒体服务器导入",
                                    if (busy) "正在导入…" else "合并 Emby / Jellyfin 清单 ›",
                                    embedded = true,
                                    icon = AppIcons.Server,
                                    onClick =
                                        if (busy) {
                                            null
                                        } else {
                                            (
                                                {
                                                    scope.launch {
                                                        busy = true
                                                        try {
                                                            personal
                                                                .importServerCollections(repo, servers)
                                                                .onSuccess {
                                                                    message =
                                                                        "已导入 " + it + " 项，现有个人选择已保留"
                                                                }.onFailure { message = it.message }
                                                        } finally {
                                                            busy = false
                                                        }
                                                    }
                                                }
                                            )
                                        },
                                )
                            }
                        }
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
                        PersonalNotice(
                            if (query.isNotBlank()) {
                                "没有匹配的记录"
                            } else if (tab ==
                                PersonalCenterTab.History
                            ) {
                                "还没有观看记录，播放后会显示在这里。"
                            } else {
                                "还没有记录，可从作品详情加入想看或收藏。"
                            },
                        )
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
private fun PersonalNotice(
    text: String,
    error: Boolean = false,
) {
    Text(
        text,
        style = AppTypography.caption.regular,
        color = if (error) LocalPalette.current.error else LocalPalette.current.sub2,
        modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
    )
}

@Composable
private fun PersonalEntryCard(
    entry: PersonalEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        SettingsCard {
            SettingRow(
                title = entry.media.title,
                value =
                    listOfNotNull(
                        entry.media.year?.toString(),
                        if (entry.collection == PersonalCollection.History) {
                            if (entry.completed) "已看完" else "看到 " + entry.positionMs / 60_000 + " 分钟"
                        } else {
                            null
                        },
                    ).joinToString(" · ").ifBlank { "查看作品" } + " ›",
                embedded = true,
                icon = if (entry.collection == PersonalCollection.History) AppIcons.Play else AppIcons.Bookmark,
                onClick = onOpen,
            )
            SettingsDivider()
            SettingRow("移除记录", "从当前资料中移除", embedded = true, onClick = onRemove)
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
            if (profile?.id != DEFAULT_PERSONAL_PROFILE) {
                SwitchRow("儿童资料", child, onChange = { child = it })
            }
            Text(
                "儿童资料需要家长 PIN，只能使用下方勾选的服务器用户。",
                style = AppTypography.caption.regular,
                color = LocalPalette.current.sub2,
            )
            SettingsCard {
                servers.forEachIndexed { index, server ->
                    if (index > 0) SettingsDivider()
                    SwitchRow(
                        title = server.serverName + " · " + server.userName,
                        checked = server.id in ids,
                        embedded = true,
                        onChange = { checked -> ids = if (checked) ids + server.id else ids - server.id },
                    )
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
    YfButton(label, onClick, enabled = enabled, tone = YfButtonTone.Secondary)
}
