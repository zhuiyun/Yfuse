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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.YfLinkButton
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import kotlinx.coroutines.launch

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
        contentPadding = PaddingValues(
            top = Dimens.contentTop,
            bottom = TabBarInset,
            start = Dimens.pageHorizontal,
            end = Dimens.pageHorizontal,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AccountHeader(onBack) }
        when (val current = state) {
            AccountState.Restoring -> item {
                AccountCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在安全恢复账号…", style = sc(12f, 500), color = palette.sub)
                    }
                }
            }

            is AccountState.RestoreFailed -> item {
                AccountCard {
                    Text("暂时无法恢复账号", style = sc(14f, 700), color = palette.text)
                    Spacer(Modifier.height(6.dp))
                    Text(current.message, style = mr(10.5f, 400), color = palette.sub)
                    Spacer(Modifier.height(10.dp))
                    YfButton(
                        label = "重试",
                        onClick = account::retryRestore,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            AccountState.SignedOut -> item { SignedOutAccountCard(account) }
            is AccountState.SignedIn -> item { SignedInAccountCard(account, current) }
        }

        item {
            AccountCard {
                Text("加密说明", style = sc(13f, 700), color = palette.text)
                Spacer(Modifier.height(7.dp))
                Text(
                    "服务器令牌、弹幕源链接、绑定和同步设置会在本机使用 AES-256-GCM " +
                        "加密后上传，服务端数据库只保存密文；加密密钥由账号密码派生。",
                    style = mr(11f, 400),
                    color = palette.sub,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "主要降低数据库或备份泄露风险；不防在线账号服务器被完全控制。同步只手动执行，" +
                        "一起看设备 ID、缓存、离线文件、诊断日志和最近搜索不会同步。",
                    style = mr(10.5f, 400),
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
    var password by remember { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var avatarId by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountCard {
        Text(
            if (registerMode) "创建 Yfuse 账号" else "登录 Yfuse 账号",
            style = sc(15f, 700),
            color = palette.text,
        )
        Spacer(Modifier.height(5.dp))
        Text("账号服务：IP 直连 · HTTPS", style = mr(10.5f, 400), color = palette.sub2)
        Spacer(Modifier.height(12.dp))

        YfFormField(
            value = username,
            onValueChange = { username = it.take(40) },
            label = "账号名",
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        Spacer(Modifier.height(9.dp))
        YfFormField(
            value = password,
            onValueChange = { password = it.take(128) },
            label = "密码（至少 $MIN_PASSWORD_LENGTH 位）",
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )

        if (registerMode) {
            Spacer(Modifier.height(9.dp))
            YfFormField(
                value = nickname,
                onValueChange = { nickname = it.take(24) },
                label = "昵称（可选）",
                enabled = !busy,
            )
            Spacer(Modifier.height(11.dp))
            Text("头像", style = sc(11f, 600), color = palette.sub)
            Spacer(Modifier.height(7.dp))
            AvatarPicker(avatarId, enabled = !busy, onSelect = { avatarId = it })
        }

        error?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, style = mr(10.5f, 500), color = Brand.Danger)
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
                    val result = if (registerMode) {
                        account.register(
                            username = username,
                            password = secret,
                            nickname = nickname.ifBlank { null },
                            avatarId = avatarId,
                        )
                    } else {
                        account.login(username, secret)
                    }
                    result.exceptionOrNull()?.let { error = it.message ?: "操作失败，请稍后重试" }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && username.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH,
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
    var uploading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(user.updatedAtEpochMs) {
        nickname = user.nickname
        avatarId = user.avatarId
    }

    AccountCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(nickname.ifBlank { user.nickname }, avatarId)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    nickname.ifBlank { user.nickname },
                    style = sc(15f, 700),
                    color = palette.text,
                    maxLines = 1,
                )
                Text("@${user.username}", style = mr(10.5f, 400), color = palette.sub2)
            }
            Text(
                if (nickname != user.nickname || avatarId != user.avatarId) "未保存" else "已登录",
                style = mr(10.5f, 600),
                color = if (nickname != user.nickname || avatarId != user.avatarId) {
                    Brand.Primary
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
                    account.updateProfile(nickname, avatarId)
                        .exceptionOrNull()
                        ?.let { localError = it.message }
                    busy = false
                }
            },
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
                enabled = !busy && currentPassword.isNotEmpty() &&
                    newPassword.length >= MIN_PASSWORD_LENGTH &&
                    confirmPassword.length >= MIN_PASSWORD_LENGTH,
                loading = busy,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    AccountCard {
        Text("加密同步", style = sc(13f, 700), color = palette.text)
        Spacer(Modifier.height(5.dp))
        Text(
            "云端版本 ${state.syncVersion}" + if (state.syncing) " · 正在同步…" else " · 手动同步",
            style = mr(10.5f, 400),
            color = palette.sub2,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = localError ?: state.message ?: syncIdleHint,
            style = mr(10.5f, 500),
            color = when {
                localError != null -> Brand.Danger
                state.message != null -> Brand.Primary
                else -> palette.sub2
            },
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            enabled = !busy && !state.syncing,
            loading = busy,
            tone = YfButtonTone.Destructive,
        )
    }

    if (confirmUpload) {
        ConfirmDialog(
            title = "用本机数据覆盖云端？",
            message = "云端当前的同步数据会被这台设备上的服务器、弹幕绑定和同步设置替换，不能撤销。",
            confirmLabel = "上传",
            onConfirm = {
                confirmUpload = false
                busy = true
                uploading = true
                localError = null
                scope.launch {
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
            message = "这台设备上的服务器、弹幕绑定和同步设置会被云端版本 ${state.syncVersion} 替换，不能撤销。",
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
}

private const val syncIdleHint =
    "不会自动上传或恢复。上传会用本机数据覆盖云端；恢复会用云端数据覆盖本机。"

@Composable
private fun AccountHeader(onBack: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .pressable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .glass(continuousRounded(12.dp), palette.card3, palette.border),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.ChevronLeft, "返回", tint = palette.text, modifier = Modifier.size(17.dp))
            }
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text("账号与同步", style = sc(20f, 700), color = palette.text)
            Text("IP HTTPS · 敏感数据加密同步", style = mr(10.5f, 400), color = palette.sub2)
        }
    }
}

@Composable
private fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card, palette.border)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        content = content,
    )
}

@Composable
private fun AvatarPicker(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(WatchTogetherPreferences.AVATAR_COUNT) { id ->
            WatchAvatar(
                avatarId = id,
                size = 38.dp,
                selected = id == selected,
                modifier = Modifier.pressable(
                    enabled = enabled,
                    haptic = HapticSignal.Select,
                    onClick = { onSelect(id) },
                ),
            )
        }
    }
}

@Composable
private fun AccountAvatar(nickname: String, avatarId: Int) {
    val initial = nickname.take(1)
    if (initial.isBlank()) {
        WatchAvatar(avatarId = avatarId, size = 44.dp)
        return
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .glass(CircleShape, Brand.Primary.copy(alpha = 0.18f), Brand.Primary.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = sc(16f, 700),
            color = Brand.Primary,
            textAlign = TextAlign.Center,
        )
    }
}
