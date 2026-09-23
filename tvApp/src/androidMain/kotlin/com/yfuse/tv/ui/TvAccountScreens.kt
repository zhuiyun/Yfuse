package com.yfuse.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.account.AccountDeviceSession
import com.yfuse.core.account.AccountState
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.feature.profile.ProfileComponent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A text field a remote can drive. Focus is remembered so the page restores the right row. */
@Composable
internal fun TvSettingsTextField(
    value: String,
    label: String,
    stableId: String,
    focusScope: String,
    focusMemory: TvUiFocusMemory,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    secret: Boolean = false,
) {
    val requester = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .then(requester)
                .onFocusChanged { if (it.isFocused) focusMemory.remember(focusScope, stableId) },
        // Material's field type is the phone's 13sp body; a remote-driven form is read from a sofa.
        textStyle = LocalTextStyle.current.copy(fontSize = TvType.body),
        label = { Text(label, fontSize = TvType.caption) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
internal fun TvAccountSettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
    onOpenSessions: () -> Unit,
) {
    val account by component.account.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focusScope = "settings:account"
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    TvSettingsPageScaffold(page = TvSettingsPage.Account, status = status) {
        when (val current = account) {
            AccountState.Restoring -> {
                item(key = "account-restoring") {
                    TvSettingsNote("正在恢复登录状态…")
                }
            }

            is AccountState.RestoreFailed -> {
                item(key = "account-restore-failed") {
                    TvSettingsNote("恢复登录失败：${current.message}", tone = TvDanger)
                }
                item(key = "account-retry") {
                    TvSettingRow(
                        title = "重试恢复",
                        value = "",
                        stableId = "account:retry",
                        focusMemory = focusMemory,
                        onClick = component.account::retryRestore,
                        icon = AppIcons.Refresh,
                        focusScope = focusScope,
                        focusRequester = firstRowRequester,
                        navigationRequester = navigationRequester,
                    )
                }
            }

            AccountState.SignedOut -> {
                item(key = "account-signed-out-note") {
                    TvSettingsNote(
                        "登录后可在多台设备之间同步播放进度、收藏与服务器配置。同步内容在离开设备前已加密。",
                    )
                }
                item(key = "account-username") {
                    TvSettingsTextField(
                        value = username,
                        label = "账号",
                        stableId = "account:username",
                        focusScope = focusScope,
                        focusMemory = focusMemory,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        focusRequester = firstRowRequester,
                    )
                }
                item(key = "account-password") {
                    TvSettingsTextField(
                        value = password,
                        label = "密码",
                        stableId = "account:password",
                        focusScope = focusScope,
                        focusMemory = focusMemory,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        secret = true,
                    )
                }
                item(key = "account-login") {
                    TvSettingRow(
                        title = if (busy) "正在登录…" else "登录",
                        value = "",
                        stableId = "account:login",
                        focusMemory = focusMemory,
                        onClick = {
                            if (username.isBlank() || password.isEmpty()) {
                                status = "请先填写账号和密码"
                                return@TvSettingRow
                            }
                            busy = true
                            status = null
                            val secret = password.toCharArray()
                            scope.launch {
                                val result = component.account.login(username.trim(), secret)
                                secret.fill('\u0000')
                                busy = false
                                status =
                                    result.fold(
                                        onSuccess = {
                                            password = ""
                                            "登录成功"
                                        },
                                        onFailure = { "登录失败：${it.message ?: "请稍后重试"}" },
                                    )
                            }
                        },
                        icon = AppIcons.User,
                        focusScope = focusScope,
                        enabled = !busy,
                        navigationRequester = navigationRequester,
                    )
                }
                item(key = "account-register-note") {
                    TvSettingsNote("注册新账号需要邀请码，请在手机端完成后再回到电视登录。")
                }
            }

            is AccountState.SignedIn -> {
                val user = current.session.user
                item(key = "account-identity") {
                    TvSettingRow(
                        title = user.nickname.ifBlank { user.username },
                        value = if (current.syncing) "同步中" else "已登录",
                        stableId = "account:identity",
                        focusMemory = focusMemory,
                        onClick = {},
                        icon = AppIcons.User,
                        focusScope = focusScope,
                        subtitle = user.username,
                        enabled = false,
                        focusRequester = firstRowRequester,
                        navigationRequester = navigationRequester,
                    )
                }
                item(key = "account-last-sync") {
                    TvSettingsNote(
                        current.lastSyncedAtEpochMs
                            ?.let { "上次同步：${formatEpoch(it)}" }
                            ?: "尚未同步过",
                    )
                }
                current.message?.let { message ->
                    item(key = "account-message") { TvSettingsNote(message, tone = TvAccent) }
                }

                item(key = "account-section-sync") { TvSettingsSectionTitle("同步") }
                item(key = "account-upload") {
                    TvSettingRow(
                        title = "立即上传",
                        value = "",
                        stableId = "account:upload",
                        focusMemory = focusMemory,
                        onClick = {
                            busy = true
                            status = null
                            scope.launch {
                                val result = component.account.uploadNow()
                                busy = false
                                status =
                                    result.fold(
                                        onSuccess = { "本机数据已上传" },
                                        onFailure = { "上传失败：${it.message ?: "请稍后重试"}" },
                                    )
                            }
                        },
                        icon = AppIcons.Cloud,
                        focusScope = focusScope,
                        subtitle = "把本机的服务器与进度写入云端",
                        enabled = !busy,
                        navigationRequester = navigationRequester,
                    )
                }
                item(key = "account-download") {
                    TvSettingRow(
                        title = "立即下载",
                        value = "",
                        stableId = "account:download",
                        focusMemory = focusMemory,
                        onClick = {
                            busy = true
                            status = null
                            scope.launch {
                                val result = component.account.downloadNow()
                                busy = false
                                status =
                                    result.fold(
                                        onSuccess = { "云端数据已应用到本机" },
                                        onFailure = { "下载失败：${it.message ?: "请稍后重试"}" },
                                    )
                            }
                        },
                        icon = AppIcons.Download,
                        focusScope = focusScope,
                        subtitle = "用云端数据覆盖本机的服务器与进度",
                        enabled = !busy,
                        navigationRequester = navigationRequester,
                    )
                }

                item(key = "account-section-devices") { TvSettingsSectionTitle("设备") }
                item(key = "account-sessions") {
                    TvSettingRow(
                        title = TvSettingsPage.AccountSessions.title,
                        value = "",
                        stableId = "account:sessions",
                        focusMemory = focusMemory,
                        onClick = onOpenSessions,
                        icon = AppIcons.Server,
                        focusScope = focusScope,
                        subtitle = TvSettingsPage.AccountSessions.subtitle,
                        navigationRequester = navigationRequester,
                    )
                }
                item(key = "account-logout") {
                    TvSettingRow(
                        title = "退出登录",
                        value = "",
                        stableId = "account:logout",
                        focusMemory = focusMemory,
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = component.account.logout()
                                busy = false
                                status =
                                    result.fold(
                                        onSuccess = { "已退出登录" },
                                        onFailure = { "退出失败：${it.message ?: "请稍后重试"}" },
                                    )
                            }
                        },
                        icon = AppIcons.Close,
                        focusScope = focusScope,
                        subtitle = "本机的离线内容与服务器配置会保留",
                        enabled = !busy,
                        navigationRequester = navigationRequester,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TvAccountSessionsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val scope = rememberCoroutineScope()
    val focusScope = "settings:sessions"
    var sessions by remember { mutableStateOf<List<AccountDeviceSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(revision) {
        loading = true
        component.account
            .sessions()
            .fold(
                onSuccess = {
                    sessions = it
                    status = null
                },
                onFailure = { status = "读取设备会话失败：${it.message ?: "请稍后重试"}" },
            )
        loading = false
    }

    TvSettingsPageScaffold(page = TvSettingsPage.AccountSessions, status = status) {
        if (loading) {
            item(key = "sessions-loading") { TvSettingsNote("正在读取…") }
        }
        if (!loading && sessions.isEmpty()) {
            item(key = "sessions-empty") { TvSettingsNote("没有其他已登录的设备。") }
        }
        sessions.forEachIndexed { index, session ->
            item(key = "session:${session.id}") {
                TvSettingRow(
                    title = session.deviceName.ifBlank { "未命名设备" },
                    value = if (session.current) "本机" else "撤销",
                    stableId = "session:${session.id}",
                    focusMemory = focusMemory,
                    onClick = {
                        if (session.current) return@TvSettingRow
                        scope.launch {
                            component.account
                                .revokeSession(session.id)
                                .fold(
                                    onSuccess = {
                                        status = "已撤销 ${session.deviceName}"
                                        revision++
                                    },
                                    onFailure = { status = "撤销失败：${it.message ?: "请稍后重试"}" },
                                )
                        }
                    },
                    icon = if (session.current) AppIcons.Check else AppIcons.Close,
                    focusScope = focusScope,
                    subtitle = "最近活动 ${formatEpoch(session.lastSeenAtEpochMs)}",
                    enabled = !session.current,
                    selected = session.current,
                    focusRequester = if (index == 0) firstRowRequester else null,
                    navigationRequester = navigationRequester,
                )
            }
        }
        if (sessions.any { !it.current }) {
            item(key = "sessions-revoke-others") {
                TvSettingRow(
                    title = "撤销其他所有设备",
                    value = "",
                    stableId = "session:revoke-others",
                    focusMemory = focusMemory,
                    onClick = {
                        scope.launch {
                            component.account
                                .revokeOtherSessions()
                                .fold(
                                    onSuccess = {
                                        status = "其他设备已全部退出"
                                        revision++
                                    },
                                    onFailure = { status = "撤销失败：${it.message ?: "请稍后重试"}" },
                                )
                        }
                    },
                    icon = AppIcons.Lock,
                    focusScope = focusScope,
                    subtitle = "本机保持登录",
                    navigationRequester = navigationRequester,
                )
            }
        }
        item(key = "sessions-refresh") {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    TvSettingsNote("撤销后，该设备下次访问云端时会被要求重新登录。")
                }
            }
        }
    }
}

private val sessionTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

internal fun formatEpoch(epochMs: Long): String = sessionTimeFormat.format(Date(epochMs))
