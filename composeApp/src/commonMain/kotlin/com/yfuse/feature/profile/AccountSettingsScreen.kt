package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.account.AccountDeviceSession
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.account.IssuedInviteCode
import com.yfuse.core.account.canIssueInvites
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.YfLinkButton
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.util.rememberShareHandler
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.flatGlass as glass

/** Mirrors the minimum the repository and the account service both enforce. */
private const val MIN_PASSWORD_LENGTH = 8

@Composable
internal fun AccountSettingsScreen(
    account: AccountRepository,
    onBack: () -> Unit,
) {
    val state by account.state.collectAsState()
    val palette = LocalPalette.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding =
            PaddingValues(
                top = SettingsHeaderTop,
                bottom = TabBarInset,
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AccountHeader(onBack) }
        when (val current = state) {
            AccountState.Restoring ->
                item {
                    AccountCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在安全恢复账号…", style = AppTypography.body.medium, color = palette.sub)
                        }
                    }
                }

            is AccountState.RestoreFailed ->
                item {
                    AccountCard {
                        Text("暂时无法恢复账号", style = AppTypography.body.strong, color = palette.text)
                        Spacer(Modifier.height(6.dp))
                        Text(current.message, style = AppTypography.caption.regular, color = palette.sub)
                        Spacer(Modifier.height(10.dp))
                        YfButton(
                            label = "重试",
                            onClick = account::retryRestore,
                        )
                    }
                }

            AccountState.SignedOut ->
                item {
                    SignedOutAccountCard(account)
                }

            is AccountState.SignedIn ->
                item {
                    SignedInAccountCard(account, current)
                }
        }

        item {
            AccountCard {
                Text("加密说明", style = AppTypography.body.strong, color = palette.text)
                Spacer(Modifier.height(7.dp))
                Text(
                    "服务器令牌、弹幕源链接、绑定和同步设置会在本机使用 AES-256-GCM " +
                        "加密后上传，服务端数据库只保存密文；加密密钥由账号密码派生。",
                    style = AppTypography.caption.regular,
                    color = palette.sub,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "主要降低数据库或备份泄露风险；不防在线账号服务器被完全控制。同步只手动执行，" +
                        "一起看设备 ID、缓存、离线文件、诊断日志和最近搜索不会同步。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
        }
    }
}

@Composable
private fun SignedOutAccountCard(account: AccountRepository) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords must not enter Android's saved-instance-state Bundle.
    var password by remember { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var avatarId by rememberSaveable { mutableStateOf(0) }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountCard {
        Text(
            if (registerMode) "创建 Yfuse 账号" else "登录 Yfuse 账号",
            style = AppTypography.body.strong,
            color = palette.text,
        )
        Spacer(Modifier.height(5.dp))
        Text("账号服务：IP 直连 · HTTPS", style = AppTypography.caption.regular, color = palette.sub2)
        Spacer(Modifier.height(12.dp))
        YfFormField(
            value = username,
            onValueChange = { username = it.take(40) },
            label = "账号名",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            enabled = !busy,
        )
        Spacer(Modifier.height(9.dp))
        YfFormField(
            value = password,
            onValueChange = { password = it.take(128) },
            label = "密码（至少 $MIN_PASSWORD_LENGTH 位）",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy,
        )
        if (registerMode) {
            Spacer(Modifier.height(9.dp))
            YfFormField(
                value = inviteCode,
                onValueChange = { inviteCode = it.trim().take(128) },
                label = "邀请码",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                enabled = !busy,
            )
            Spacer(Modifier.height(9.dp))
            YfFormField(
                value = nickname,
                onValueChange = { nickname = it.take(24) },
                label = "昵称（可选）",
                enabled = !busy,
            )
            Spacer(Modifier.height(11.dp))
            Text("头像", style = AppTypography.caption.strong, color = palette.sub)
            Spacer(Modifier.height(7.dp))
            AvatarPicker(avatarId, enabled = !busy, onSelect = { avatarId = it })
        }
        error?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, style = AppTypography.caption.medium, color = palette.error)
        }
        Spacer(Modifier.height(13.dp))
        YfButton(
            label = if (registerMode) "注册账号" else "登录账号",
            onClick = {
                busy = true
                error = null
                val secret = password.toCharArray()
                password = ""
                scope.launch {
                    val result =
                        if (registerMode) {
                            account.register(
                                username = username,
                                password = secret,
                                nickname = nickname.ifBlank { null },
                                avatarId = avatarId,
                                inviteCode = inviteCode,
                            )
                        } else {
                            account.login(username, secret)
                        }
                    result.exceptionOrNull()?.let { error = it.message ?: "操作失败，请稍后重试" }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled =
                !busy &&
                    username.isNotBlank() &&
                    password.length >= MIN_PASSWORD_LENGTH &&
                    (!registerMode || inviteCode.length >= 12),
            loading = busy,
        )
        YfLinkButton(
            label = if (registerMode) "已有账号？去登录" else "没有账号？创建一个",
            onClick = {
                registerMode = !registerMode
                password = ""
                error = null
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy,
        )
    }
}

@Composable
private fun SignedInAccountCard(
    account: AccountRepository,
    state: AccountState.SignedIn,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val scope = rememberCoroutineScope()
    val user = state.session.user
    var nickname by rememberSaveable(user.id) { mutableStateOf(user.nickname) }
    var avatarId by rememberSaveable(user.id) { mutableStateOf(user.avatarId) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showPasswordForm by remember { mutableStateOf(false) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var confirmClearRemote by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    // Which of the two is running, so the button that was pressed carries the spinner and
    // the other merely dims. `busy` alone disabled the whole form at once, which recoloured
    // every control on the card for the length of a fast request — the other half of the
    // flashing.
    var uploading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf<List<AccountDeviceSession>>(emptyList()) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var confirmRevokeOthers by remember { mutableStateOf(false) }
    var confirmRevokeAll by remember { mutableStateOf(false) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    // Invite plaintext is intentionally not saveable and is discarded as soon as its dialog
    // closes. The service cannot show the same code again.
    var issuedInvite by remember { mutableStateOf<IssuedInviteCode?>(null) }
    var inviteBusy by remember { mutableStateOf(false) }
    val share = rememberShareHandler()

    fun reloadSessions() {
        sessionsLoading = true
        scope.launch {
            account
                .sessions()
                .onSuccess { sessions = it }
                .onFailure { localError = it.message ?: "无法读取登录设备" }
            sessionsLoading = false
        }
    }

    LaunchedEffect(user.updatedAtEpochMs) {
        nickname = user.nickname
        avatarId = user.avatarId
    }

    AccountCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The *edited* nickname and avatar, not the saved ones.
            //
            // These two showed `user.…`, which is what the server last confirmed, while the
            // picker below edited local state — so choosing an avatar moved the selection
            // ring and left the avatar beside the name on the old one until 保存资料 came
            // back. It read as the tap not registering. A picker and its preview have to be
            // the same value; 保存资料 is what makes that value permanent, not what makes it
            // visible.
            AccountAvatar(nickname.ifBlank { user.nickname }, avatarId)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    nickname.ifBlank { user.nickname },
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 1,
                )
                Text("@${user.username}", style = AppTypography.caption.regular, color = palette.sub2)
            }
            Text(
                if (nickname != user.nickname || avatarId != user.avatarId) "未保存" else "已登录",
                style = AppTypography.caption.strong,
                color =
                    if (nickname != user.nickname || avatarId != user.avatarId) {
                        accent.accent
                    } else {
                        Brand.Online
                    },
            )
        }
        Spacer(Modifier.height(14.dp))
        YfFormField(
            value = nickname,
            onValueChange = { nickname = it.take(24) },
            label = "昵称",
            enabled = !busy && !state.syncing,
        )
        Spacer(Modifier.height(10.dp))
        AvatarPicker(
            selected = avatarId,
            enabled = !busy && !state.syncing,
            onSelect = { avatarId = it },
        )
        Spacer(Modifier.height(12.dp))
        YfButton(
            label = "保存资料",
            onClick = {
                busy = true
                localError = null
                scope.launch {
                    account
                        .updateProfile(nickname, avatarId)
                        .exceptionOrNull()
                        ?.let { localError = it.message }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !state.syncing && nickname.isNotBlank(),
            loading = busy && !state.syncing,
        )
        YfLinkButton(
            label = if (showPasswordForm) "取消修改密码" else "修改登录密码",
            onClick = {
                showPasswordForm = !showPasswordForm
                currentPassword = ""
                newPassword = ""
                confirmPassword = ""
                localError = null
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy && !state.syncing,
        )
        if (showPasswordForm) {
            YfFormField(
                value = currentPassword,
                onValueChange = { currentPassword = it.take(128) },
                label = "当前密码",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            Spacer(Modifier.height(9.dp))
            YfFormField(
                value = newPassword,
                onValueChange = { newPassword = it.take(128) },
                label = "新密码（至少 $MIN_PASSWORD_LENGTH 位）",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            Spacer(Modifier.height(9.dp))
            YfFormField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it.take(128) },
                label = "确认新密码",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            Spacer(Modifier.height(10.dp))
            YfButton(
                label = "确认修改密码",
                onClick = {
                    if (newPassword != confirmPassword) {
                        localError = "两次输入的新密码不一致"
                    } else {
                        busy = true
                        localError = null
                        val currentSecret = currentPassword.toCharArray()
                        val newSecret = newPassword.toCharArray()
                        currentPassword = ""
                        newPassword = ""
                        confirmPassword = ""
                        scope.launch {
                            val result = account.changePassword(currentSecret, newSecret)
                            result.exceptionOrNull()?.let { localError = it.message ?: "修改密码失败" }
                            if (result.isSuccess) showPasswordForm = false
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled =
                    !busy &&
                        currentPassword.isNotEmpty() &&
                        newPassword.length >= MIN_PASSWORD_LENGTH &&
                        confirmPassword.length >= MIN_PASSWORD_LENGTH,
                loading = busy,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    if (user.canIssueInvites()) {
        AccountCard {
            Text("注册邀请", style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(5.dp))
            Text(
                "生成一次性邀请码。明文只会显示这一次，请立即复制并安全发送。",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
            Spacer(Modifier.height(10.dp))
            YfButton(
                label = if (inviteBusy) "正在生成…" else "生成邀请码",
                onClick = {
                    inviteBusy = true
                    localError = null
                    scope.launch {
                        account
                            .issueInvite()
                            .onSuccess { issuedInvite = it }
                            .onFailure { localError = it.message ?: "生成邀请码失败" }
                        inviteBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !state.syncing && !inviteBusy,
                loading = inviteBusy,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    AccountCard {
        Text("登录设备与会话", style = AppTypography.body.strong, color = palette.text)
        Spacer(Modifier.height(5.dp))
        Text(
            if (sessions.isEmpty()) "点下方按钮读取当前有效会话" else "共 ${sessions.size} 个有效会话",
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        sessions.forEach { session ->
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.deviceName + if (session.current) " · 当前设备" else "",
                        style = AppTypography.caption.strong,
                        color = if (session.current) Brand.Online else palette.text,
                    )
                    Text(
                        "最近活动 ${formatSessionActivity(session.lastSeenAtEpochMs)}",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
                YfLinkButton(
                    label = "撤销",
                    onClick = {
                        busy = true
                        scope.launch {
                            account
                                .revokeSession(session.id)
                                .onSuccess { sessions = sessions.filterNot { it.id == session.id } }
                                .onFailure { localError = it.message ?: "撤销失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        YfButton(
            label = if (sessionsLoading) "正在读取…" else "刷新登录设备",
            onClick = ::reloadSessions,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !sessionsLoading,
            loading = sessionsLoading,
        )
        YfLinkButton(
            label = "退出其他设备",
            onClick = { confirmRevokeOthers = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy,
        )
        YfLinkButton(
            label = "全部设备退出（包括当前设备）",
            onClick = { confirmRevokeAll = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy,
        )
    }

    Spacer(Modifier.height(16.dp))
    AccountCard {
        Text("账号数据", style = AppTypography.body.strong, color = palette.text)
        Spacer(Modifier.height(5.dp))
        Text(
            "导出仅含账号资料和端到端加密同步信封，不含密码或登录令牌。",
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        Spacer(Modifier.height(10.dp))
        YfButton(
            label = "安全导出账号数据",
            onClick = {
                busy = true
                scope.launch {
                    account
                        .exportAccount()
                        .onSuccess(share::shareText)
                        .onFailure { localError = it.message ?: "导出失败" }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        YfLinkButton(
            label = "永久删除账号",
            onClick = { confirmDeleteAccount = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy,
        )
    }

    Spacer(Modifier.height(16.dp))
    AccountCard {
        Text("加密同步", style = AppTypography.body.strong, color = palette.text)
        Spacer(Modifier.height(5.dp))
        Text(
            "云端版本 ${state.syncVersion}" +
                if (state.syncing) " · 正在同步…" else " · 手动同步",
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        // One status line that is always here.
        //
        // This was two conditional `Text`s — a success message and an error — each of which
        // appeared with its own spacer and disappeared again. Every action grew the card,
        // and the next one shrank it, which shifted the two buttons and everything below
        // them: that is the flashing. The slot is now reserved whether or not it has
        // anything to say, so pressing a button changes what the card *says* and never how
        // tall it is.
        Spacer(Modifier.height(7.dp))
        Text(
            text = localError ?: state.message ?: SYNC_IDLE_HINT,
            style = AppTypography.caption.medium,
            color =
                when {
                    localError != null -> palette.error
                    state.message != null -> accent.accent
                    else -> palette.sub2
                },
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 上传 and 恢复 each overwrite one whole side with the other, and both used to do
            // it on a single tap sitting inside a two-button row. The warning was printed
            // under them, which is exactly the place a warning is not read. Neither is
            // reversible and neither is urgent, so both now ask.
            OverlayButton(
                label = "上传本机",
                onClick = { confirmUpload = true },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = !busy && !state.syncing,
                loading = uploading,
            )
            OverlayButton(
                label = "恢复云端",
                onClick = { confirmDownload = true },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = !busy && !state.syncing && state.cloudHasData,
                loading = downloading,
            )
        }
        OverlayButton(
            label = "清空服务器数据",
            onClick = { confirmClearRemote = true },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            tone = OverlayButtonTone.Destructive,
            enabled = !busy && !state.syncing && state.cloudHasData,
        )
    }

    Spacer(Modifier.height(16.dp))
    AccountCard {
        YfButton(
            label = "退出 Yfuse 账号",
            onClick = {
                busy = true
                scope.launch {
                    account.logout()
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !state.syncing,
            loading = busy,
            tone = YfButtonTone.Destructive,
        )
    }

    if (confirmUpload) {
        ConfirmDialog(
            title = "用本机数据覆盖云端？",
            message =
                "云端当前的同步数据会被这台设备上的服务器、弹幕绑定和同步设置替换，" +
                    "不能撤销。",
            confirmLabel = "上传",
            onConfirm = {
                confirmUpload = false
                busy = true
                uploading = true
                localError = null
                scope.launch {
                    // Failures already surface through the repository's status message.
                    account.uploadNow()
                    uploading = false
                    busy = false
                }
            },
            onDismiss = { confirmUpload = false },
        )
    }

    if (confirmDownload) {
        ConfirmDialog(
            title = "用云端数据覆盖本机？",
            message =
                "这台设备上的服务器、弹幕绑定和同步设置会被云端版本 " +
                    "${state.syncVersion} 替换，不能撤销。",
            confirmLabel = "恢复",
            onConfirm = {
                confirmDownload = false
                busy = true
                downloading = true
                localError = null
                scope.launch {
                    account.downloadNow()
                    downloading = false
                    busy = false
                }
            },
            onDismiss = { confirmDownload = false },
        )
    }

    if (confirmClearRemote) {
        // Was the app's last stock Material `AlertDialog` — an opaque M3 surface with M3
        // typography and radii, in the middle of a glass app. Same question, same two ways
        // out, in the one overlay material everything else uses.
        ConfirmDialog(
            title = "清空服务器数据？",
            message = "只删除这个账号的云端同步密文；账号、昵称头像和本机数据都会保留。",
            confirmLabel = "确认清空",
            destructive = true,
            onConfirm = {
                confirmClearRemote = false
                busy = true
                localError = null
                scope.launch {
                    val result = account.clearRemoteSync()
                    result.exceptionOrNull()?.let { localError = it.message ?: "清空失败" }
                    busy = false
                }
            },
            onDismiss = { confirmClearRemote = false },
        )
    }

    if (confirmRevokeOthers) {
        ConfirmDialog(
            title = "退出其他设备？",
            message = "其他设备的访问令牌和刷新令牌会立即失效，当前设备保持登录。",
            confirmLabel = "确认退出",
            destructive = true,
            onConfirm = {
                confirmRevokeOthers = false
                scope.launch { account.revokeOtherSessions().onSuccess { reloadSessions() } }
            },
            onDismiss = { confirmRevokeOthers = false },
        )
    }

    if (confirmRevokeAll) {
        ConfirmDialog(
            title = "全部设备退出？",
            message = "包括当前设备在内的所有会话都会立即失效，需要重新登录。",
            confirmLabel = "全部退出",
            destructive = true,
            onConfirm = {
                confirmRevokeAll = false
                scope.launch { account.revokeAllSessions() }
            },
            onDismiss = { confirmRevokeAll = false },
        )
    }

    if (confirmDeleteAccount) {
        GlassDialog(onDismiss = {
            deletePassword = ""
            confirmDeleteAccount = false
        }) {
            Text("永久删除账号？", style = AppTypography.section.strong, color = palette.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "账号、所有登录会话及云端加密数据将永久删除。此操作不能撤销。",
                style = AppTypography.body.regular,
                color = palette.body,
            )
            Spacer(Modifier.height(10.dp))
            YfFormField(
                value = deletePassword,
                onValueChange = { deletePassword = it.take(128) },
                label = "当前密码",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(12.dp))
            YfButton(
                label = "永久删除",
                tone = YfButtonTone.Destructive,
                modifier = Modifier.fillMaxWidth(),
                enabled = deletePassword.isNotEmpty(),
                onClick = {
                    val password = deletePassword.toCharArray()
                    deletePassword = ""
                    confirmDeleteAccount = false
                    scope.launch {
                        account
                            .deleteAccount(password)
                            .onFailure { localError = it.message ?: "账号删除失败" }
                    }
                },
            )
        }
    }

    issuedInvite?.let { invite ->
        GlassDialog(
            onDismiss = { issuedInvite = null },
        ) {
            Text("邀请码已生成", style = AppTypography.section.strong, color = palette.text)
            Spacer(Modifier.height(7.dp))
            Text(
                "这是一次性明文，关闭后无法再次查看。",
                style = AppTypography.caption.medium,
                color = palette.error,
            )
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    invite.code,
                    modifier = Modifier.fillMaxWidth(),
                    style = AppTypography.body.strong,
                    color = accent.accent,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                "有效期至 ${formatInviteExpiryUtc(invite.expiresAtEpochMs)}",
                modifier = Modifier.fillMaxWidth(),
                style = AppTypography.caption.regular,
                color = palette.sub2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(13.dp))
            YfButton(
                label = "复制邀请码",
                onClick = { share.copyText(invite.code) },
                modifier = Modifier.fillMaxWidth(),
            )
            YfLinkButton(
                label = "关闭（不再显示）",
                onClick = { issuedInvite = null },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

internal fun formatInviteExpiryUtc(epochMs: Long): String {
    val dayMs = 86_400_000L
    val hourMs = 3_600_000L
    val minuteMs = 60_000L
    var days = epochMs.coerceAtLeast(0L) / dayMs + 719_468L
    val era = days / 146_097L
    val dayOfEra = days - era * 146_097L
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    if (month <= 2L) year++
    val withinDay = epochMs.coerceAtLeast(0L) % dayMs
    val hour = withinDay / hourMs
    val minute = (withinDay % hourMs) / minuteMs
    return buildString {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
        append(' ')
        append(hour.toString().padStart(2, '0'))
        append(':')
        append(minute.toString().padStart(2, '0'))
        append(" UTC")
    }
}

private fun formatSessionActivity(
    epochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
): String {
    val elapsedMs = (nowEpochMs - epochMs).coerceAtLeast(0L)
    return when {
        elapsedMs < 60_000L -> "刚刚"
        elapsedMs < 60 * 60_000L -> "${elapsedMs / 60_000L} 分钟前"
        elapsedMs < 24 * 60 * 60_000L -> "${elapsedMs / (60 * 60_000L)} 小时前"
        elapsedMs < 30L * 24 * 60 * 60_000L -> "${elapsedMs / (24 * 60 * 60_000L)} 天前"
        else -> "较早"
    }
}

/**
 * What the sync card's status line says when it has no news.
 *
 * It is the warning that used to sit *under* the two buttons, which is where a warning about
 * what a button does goes unread. Standing it in the slot the buttons report into means it is
 * the thing you are already looking at when you reach for them, and it keeps that slot from
 * being empty — see the card for why the height has to be constant.
 */
private const val SYNC_IDLE_HINT =
    "不会自动上传或恢复。上传会用本机数据覆盖云端；恢复会用云端数据覆盖本机。"

@Composable
private fun AccountHeader(onBack: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            // Past the list's own inset, into the corner — see [SettingsBackButton].
            .offset(x = SettingsBackInset - Dimens.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsBackButton(onBack)
        Column(Modifier.padding(start = 10.dp)) {
            Text("账号与同步", style = AppTypography.section.strong, color = palette.text)
            Text("IP HTTPS · 敏感数据加密同步", style = AppTypography.caption.regular, color = palette.sub2)
        }
    }
}

@Composable
private fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .glass(AppShapes.card, palette.card, palette.border)
                .padding(horizontal = 16.dp, vertical = 15.dp),
        content = content,
    )
}

/**
 * The eight avatars, drawn as the avatars they are.
 *
 * This used to render `id + 1` — eight grey circles reading 1 to 8 — while the very same
 * ids are drawn as 🍿 🎬 🌙 🚀 🐱 🐼 🦊 ✨ everywhere 一起看 shows a participant. Picking
 * one here therefore told the user nothing about what they had picked, and the number
 * they were choosing between existed in no other part of the app.
 */
@Composable
private fun AvatarPicker(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(WatchTogetherPreferences.AVATAR_COUNT) { id ->
            // WatchAvatar carries its own selected ring, so there is no glass layer here.
            WatchAvatar(
                avatarId = id,
                size = 38.dp,
                selected = id == selected,
                modifier =
                    Modifier
                        .pressable(
                            enabled = enabled,
                            haptic = HapticSignal.Select,
                            role = Role.RadioButton,
                            onClick = { onSelect(id) },
                        ).semantics {
                            this.selected = id == selected
                            contentDescription = "头像 ${id + 1}"
                        }.touchTarget(),
            )
        }
    }
}

/**
 * The signed-in identity: the first character of the nickname, or the chosen avatar when
 * there is no nickname to take one from.
 *
 * The fallback was `avatarId + 1`, which put a bare digit where every other surface in the
 * app shows this account's actual avatar.
 */
@Composable
private fun AccountAvatar(
    nickname: String,
    avatarId: Int,
) {
    val accent = LocalAccentColors.current
    val initial = nickname.take(1)
    if (initial.isBlank()) {
        WatchAvatar(avatarId = avatarId, size = 44.dp)
        return
    }
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .glass(CircleShape, accent.container, accent.border),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = AppTypography.section.strong,
            color = accent.accent,
            textAlign = TextAlign.Center,
        )
    }
}
