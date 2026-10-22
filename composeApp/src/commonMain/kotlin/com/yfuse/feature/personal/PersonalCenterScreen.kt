package com.yfuse.feature.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.backend.BackendAccess
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.ContextualTip
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OperationFeedback
import com.yfuse.core.designsystem.OperationTone
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.SwitchRow
import com.yfuse.core.designsystem.Tips
import com.yfuse.core.designsystem.ToastAction
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.returnLocator
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.DEFAULT_PERSONAL_PROFILE
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.PersonalProfile
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.SyncMutationKind
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.feature.profile.SettingsPage
import com.yfuse.feature.profile.rememberComposedPageStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/**
 * 我的内容, 家庭资料 or 同步状态与恢复 — [initialTab] says which — drawn from [store]. [servers] are
 * the ones the page offers the profile: the editor's list, and what 从媒体服务器导入 reads.
 */
@Composable
fun PersonalCenterScreen(
    store: Store<PersonalCenterIntent, PersonalCenterState, Nothing>,
    servers: List<SavedServer>,
    onBack: () -> Unit,
    onOpenMedia: (PersonalMediaRef) -> Unit,
    initialTab: PersonalCenterTab = PersonalCenterTab.WatchLater,
) {
    val state by store.states.collectAsState(store.state)
    // Whether the page has records a finger could swipe now; the tip waits for them.
    var swipeable by remember { mutableStateOf(false) }
    // Leaving the page is the toast leaving too: nothing stays held behind a closed page.
    DisposableEffect(store) {
        onDispose { store.accept(PersonalCenterIntent.SettleRemoval) }
    }
    Box(Modifier.fillMaxSize()) {
        if (initialTab in PersonalContentTabs) {
            PersonalContentScreen(
                state = state,
                onIntent = store::accept,
                servers = servers,
                onBack = onBack,
                onOpenMedia = onOpenMedia,
                initialTab = initialTab,
                onSwipeable = { swipeable = it },
            )
        } else {
            PersonalCenterPage(
                state = state,
                onIntent = store::accept,
                servers = servers,
                onBack = onBack,
                initialTab = initialTab,
            )
        }
        // Once a list has records to swipe; the first swipe retires it.
        ContextualTip(
            id = Tips.SWIPE_ROW_HISTORY,
            text = "向左滑动记录可以移除，5 秒内可撤销",
            active = swipeable,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.sectionGap),
        )
        key(state.removalGeneration) {
            val pending = state.pendingRemoval
            ActionToast(
                message = pending?.let { personalRemovalMessage(it.collection, it.media.title) },
                onDismiss = { store.accept(PersonalCenterIntent.SettleRemoval) },
                action =
                    pending?.let { entry ->
                        ToastAction("撤销") { store.accept(PersonalCenterIntent.UndoRemoval(entry)) }
                    },
            )
        }
    }
}

/**
 * The same pages for the television's settings, which swap their pages in place rather than
 * through the phone's page stack; the store lives as long as the page is composed there.
 */
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
    val store =
        rememberComposedPageStore(personal, account, playbackSync, serverSync, repo) {
            PersonalCenterStoreFactory(
                // The app's StoreFactory is this one; the television reaches the page without it.
                storeFactory = DefaultStoreFactory(),
                personal = personal,
                sync = PersonalSync(account, playbackSync, serverSync),
                repo = repo,
            ).create()
        }
    PersonalCenterScreen(
        store = store,
        servers = servers,
        onBack = onBack,
        onOpenMedia = onOpenMedia,
        initialTab = initialTab,
    )
}

@Composable
private fun PersonalCenterPage(
    state: PersonalCenterState,
    onIntent: (PersonalCenterIntent) -> Unit,
    servers: List<SavedServer>,
    onBack: () -> Unit,
    initialTab: PersonalCenterTab,
) {
    val library = state.library
    val playbackState = state.playbackSync
    val serverState = state.serverSync
    SettingsPage(
        title = initialTab.label,
        subtitle = library.activeProfile.name,
        onBack = onBack,
    ) {
        if (state.busy) {
            item {
                OperationFeedback(
                    "正在保存更改…",
                    OperationTone.Working,
                    Modifier.padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
        state.message?.let { notice ->
            item {
                OperationFeedback(
                    notice,
                    OperationTone.Success,
                    Modifier.padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
        state.failure?.let { failed ->
            item {
                ErrorState(
                    message = failed,
                    onRetry = { onIntent(PersonalCenterIntent.RetryFailure) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // A failed 立即同步 also leaves its reason here; the card above already says it.
        library.error?.takeIf { it != state.failure }?.let { notice -> item { PersonalNotice(notice, error = true) } }
        when (initialTab) {
            PersonalCenterTab.Profiles -> {
                item { PersonalNotice("每份资料分别保存想看、收藏、观看历史和追剧。") }
                item { PersonalNotice("需要服务器观看记录也独立时，请为家庭成员关联不同的服务器用户。") }
                item {
                    Section(title = "管理资料") {
                        SettingsCard {
                            SettingRow(
                                "新建家庭资料",
                                if (library.activeProfile.child) "请切换至成人资料" else "添加家庭成员",
                                embedded = true,
                                icon = AppIcons.User,
                                iconTint = SettingTint.account,
                                onClick =
                                    if (library.activeProfile.child ||
                                        state.busy
                                    ) {
                                        null
                                    } else {
                                        (
                                            {
                                                onIntent(PersonalCenterIntent.EditProfile(null))
                                            }
                                        )
                                    },
                            )
                            SettingsDivider()
                            SettingRow(
                                if (library.hasGuardianPin) "修改家长 PIN" else "设置家长 PIN",
                                "儿童资料与切换保护",
                                embedded = true,
                                icon = AppIcons.Lock,
                                iconTint = SettingTint.account,
                                onClick =
                                    if (state.busy) {
                                        null
                                    } else {
                                        (
                                            {
                                                onIntent(PersonalCenterIntent.EditGuardianPin)
                                            }
                                        )
                                    },
                            )
                        }
                    }
                }
                motionItems(library.profiles, key = { it.id }) { profile ->
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
                                icon = AppIcons.Server,
                                iconTint = SettingTint.servers,
                            )
                            SettingsDivider()
                            SettingRow(
                                "当前资料",
                                icon = AppIcons.User,
                                iconTint = SettingTint.account,
                                modifier = Modifier.returnLocator(profile.id == library.activeProfile.id),
                                loading = state.busy && profile.id != library.activeProfile.id,
                                value =
                                    if (profile.id ==
                                        library.activeProfile.id
                                    ) {
                                        "正在使用"
                                    } else {
                                        "切换到此资料"
                                    },
                                embedded = true,
                                onClick =
                                    if (profile.id ==
                                        library.activeProfile.id ||
                                        state.busy
                                    ) {
                                        null
                                    } else {
                                        (
                                            {
                                                onIntent(PersonalCenterIntent.SwitchProfile(profile))
                                            }
                                        )
                                    },
                            )
                            if (!library.activeProfile.child) {
                                SettingsDivider()
                                SettingRow(
                                    "编辑资料",
                                    "名称、类型与服务器权限",
                                    embedded = true,
                                    icon = AppIcons.Edit,
                                    iconTint = SettingTint.account,
                                    onClick = { onIntent(PersonalCenterIntent.EditProfile(profile)) },
                                )
                                if (profile.id != DEFAULT_PERSONAL_PROFILE && profile.id != library.activeProfile.id) {
                                    SettingsDivider()
                                    SettingRow(
                                        "移除资料",
                                        "移除此家庭成员",
                                        embedded = true,
                                        icon = AppIcons.Remove,
                                        iconTint = LocalPalette.current.error,
                                        destructive = true,
                                        onClick = { onIntent(PersonalCenterIntent.AskRemoveProfile(profile)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            PersonalCenterTab.Sync -> {
                if (BackendAccess.Default.enabled) {
                    item {
                        Section(title = "个人内容") {
                            SettingsCard {
                                SettingRow(
                                    "清单、历史与追剧",
                                    when {
                                        library.syncing -> "正在同步…"
                                        library.pendingSync -> "有本机更改待同步"
                                        else -> "本机更改已同步"
                                    },
                                    embedded = true,
                                )
                                SettingsDivider()
                                SettingRow(
                                    "最近成功",
                                    library.lastSyncedAtEpochMs?.let {
                                        Instant
                                            .ofEpochMilli(it)
                                            .atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                    }
                                        ?: "暂无同步记录",
                                    embedded = true,
                                )
                                SettingsDivider()
                                SettingRow(
                                    "立即同步",
                                    loading = library.syncing,
                                    value = if (!state.signedIn) "请先登录鱼服账号" else "合并个人数据并重试",
                                    embedded = true,
                                    icon = AppIcons.Refresh,
                                    iconTint = SettingTint.sync,
                                    onClick =
                                        if (!state.signedIn ||
                                            library.syncing
                                        ) {
                                            null
                                        } else {
                                            (
                                                {
                                                    onIntent(PersonalCenterIntent.SyncNow)
                                                }
                                            )
                                        },
                                )
                            }
                        }
                    }
                    item { PersonalNotice("个人内容自动加密合并，保留删除记录；服务器配置与设置备份仍需手动操作。") }
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
                                        Instant
                                            .ofEpochMilli(it)
                                            .atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                    }
                                        ?: "暂无同步记录",
                                    embedded = true,
                                )
                                SettingsDivider()
                                SettingRow(
                                    "刷新与重试",
                                    loading = playbackState.syncing,
                                    value = "拉取最新播放进度",
                                    embedded = true,
                                    icon = AppIcons.Refresh,
                                    iconTint = SettingTint.sync,
                                    onClick =
                                        if (playbackState.syncing ||
                                            !state.signedIn
                                        ) {
                                            null
                                        } else {
                                            (
                                                {
                                                    onIntent(PersonalCenterIntent.RefreshPlaybackSync)
                                                }
                                            )
                                        },
                                )
                            }
                        }
                    }
                    playbackState.error?.let { error -> item { PersonalNotice(error, error = true) } }
                }
                item {
                    Section(title = "媒体服务器") {
                        SettingsCard {
                            SettingRow(
                                "同步状态",
                                "待处理 " + serverState.pendingCount + " 项 · 冲突 " + serverState.conflicts.size + " 项",
                                embedded = true,
                            )
                            state.serverStatuses.forEach { status ->
                                SettingsDivider()
                                SettingRow(
                                    status.serverName,
                                    loading = status.syncing,
                                    value = status.error ?: if (status.syncing) "同步中" else "就绪",
                                    embedded = true,
                                )
                            }
                            SettingsDivider()
                            SettingRow(
                                "重试服务器同步",
                                "重新提交待处理更改",
                                embedded = true,
                                icon = AppIcons.Refresh,
                                iconTint = SettingTint.sync,
                                loading =
                                    state.serverStatuses
                                        .any {
                                            it.syncing
                                        },
                                onClick = {
                                    onIntent(PersonalCenterIntent.RetryServerSync)
                                },
                            )
                        }
                    }
                }
                motionItems(
                    state.serverConflicts,
                    key = { "${it.mutation.serverId}|${it.mutation.itemId}|${it.mutation.kind}" },
                    contentType = { "sync-conflict" },
                ) { conflict ->
                    Section(title = conflict.mutation.title) {
                        SettingsCard {
                            val kind = if (conflict.mutation.kind == SyncMutationKind.Favorite) "收藏" else "已看"
                            SettingRow(
                                kind + "冲突",
                                syncConflictValueCopy(
                                    conflict.mutation.kind,
                                    conflict.mutation.desired,
                                    conflict.serverValue,
                                ),
                                embedded = true,
                            )
                            SettingsDivider()
                            SettingRow("保留本机", "使用当前资料的选择", embedded = true, enabled = !state.busy, onClick = {
                                onIntent(PersonalCenterIntent.ResolveConflict(conflict, keepLocal = true))
                            })
                            SettingsDivider()
                            SettingRow("采用服务器", "使用服务器的选择", embedded = true, enabled = !state.busy, onClick = {
                                onIntent(PersonalCenterIntent.ResolveConflict(conflict, keepLocal = false))
                            })
                        }
                    }
                }
            }
            else -> Unit
        }
    }
    // These close once the change has gone through; held in a presence they leave the way they
    // came instead of vanishing in a frame.
    DialogPresence(state.editor) { target ->
        PersonalProfileEditor(
            profile = target.profile,
            servers = servers,
            onDismiss = { onIntent(PersonalCenterIntent.DismissEditor) },
            error = state.dialogError,
        ) { name, child, ids ->
            onIntent(PersonalCenterIntent.SaveProfile(target.profile?.id, name, child, ids))
        }
    }
    DialogPresence(state.switching) { profile ->
        PersonalPinDialog(
            title = "切换到 ${profile.name}",
            setting = false,
            onDismiss = { onIntent(PersonalCenterIntent.DismissSwitch) },
            error = state.dialogError,
        ) { pin, _ ->
            onIntent(PersonalCenterIntent.ConfirmSwitch(profile.id, pin))
        }
    }
    DialogPresence(state.settingPin.takeIf { it }) {
        PersonalPinDialog(
            title = "家长 PIN",
            setting = true,
            onDismiss = { onIntent(PersonalCenterIntent.DismissGuardianPin) },
            error = state.dialogError,
        ) { old, next ->
            onIntent(PersonalCenterIntent.SaveGuardianPin(currentPin = old, newPin = next))
        }
    }
    state.removingProfile?.let { profile ->
        ConfirmDialog(
            title = "移除家庭资料？",
            message = "“${profile.name}”的想看、收藏、观看历史和追剧会一并删除，不能撤销。",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = { onIntent(PersonalCenterIntent.RemoveProfile(profile)) },
            onDismiss = { onIntent(PersonalCenterIntent.KeepProfile) },
        )
    }
}

/** What the toast says an undoable removal took, in the words of the list it left. */
internal fun personalRemovalMessage(
    collection: PersonalCollection,
    title: String,
): String =
    when (collection) {
        PersonalCollection.WatchLater -> "已从想看移除「$title」"
        PersonalCollection.Favorite -> "已从收藏移除「$title」"
        PersonalCollection.History -> "已移除「$title」的观看记录"
    }

/**
 * Human copy for a sync conflict's two sides, phrased for what [kind] actually is — the
 * card used to print `PendingSyncMutation.desired`/`SyncConflict.serverValue` as raw
 * Booleans ("本机：true · 服务器：false"), which says nothing to someone who did not just
 * read the source.
 */
internal fun syncConflictValueCopy(
    kind: SyncMutationKind,
    desired: Boolean,
    serverValue: Boolean,
): String {
    val (onLabel, offLabel) =
        when (kind) {
            SyncMutationKind.Favorite -> "已收藏" to "未收藏"
            SyncMutationKind.Played -> "已看" to "未看"
        }

    fun label(value: Boolean) = if (value) onLabel else offLabel
    return "本机：${label(desired)} · 服务器：${label(serverValue)}"
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
private fun PersonalProfileEditor(
    profile: PersonalProfile?,
    servers: List<SavedServer>,
    onDismiss: () -> Unit,
    /** Why the last save did not go through, told where the person is looking. */
    error: String? = null,
    onSave: (String, Boolean, Set<String>) -> Unit,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var child by remember(profile?.id) { mutableStateOf(profile?.child ?: false) }
    var ids by remember(profile?.id) { mutableStateOf(profile?.serverIds.orEmpty()) }
    GlassDialog(onDismiss = onDismiss, contentPadding = 0.dp) {
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
            error?.let { PersonalDialogError(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YfButton("取消", onDismiss, modifier = Modifier.weight(1f), tone = YfButtonTone.Secondary)
                YfButton("保存", { onSave(name, child, ids) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PersonalPinDialog(
    title: String,
    setting: Boolean,
    onDismiss: () -> Unit,
    /** Why the last attempt did not go through — a wrong PIN, most often. */
    error: String? = null,
    onSubmit: (String, String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    GlassDialog(onDismiss = onDismiss, contentPadding = 0.dp) {
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
                }, label = "新 PIN（6–12 位）", visualTransformation = PasswordVisualTransformation())
            }
            error?.let { PersonalDialogError(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YfButton("取消", onDismiss, modifier = Modifier.weight(1f), tone = YfButtonTone.Secondary)
                YfButton("确认", { onSubmit(pin, next) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A dialog's own failure: in the error colour, and said at once by a screen reader. */
@Composable
private fun PersonalDialogError(text: String) {
    Text(
        text,
        style = AppTypography.caption.regular,
        color = LocalPalette.current.error,
        modifier = Modifier.liveStatus(assertive = true),
    )
}
