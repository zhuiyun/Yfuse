package com.yfuse.feature.profile

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import kotlinx.coroutines.launch

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
                    Button(onClick = account::retryRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("重试")
                    }
                }
            }

            AccountState.SignedOut -> item {
                SignedOutAccountCard(account)
            }

            is AccountState.SignedIn -> item {
                SignedInAccountCard(account, current)
            }
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
    // Passwords must not enter Android's saved-instance-state Bundle.
    var password by remember { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var avatarId by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountCard {
        Text(if (registerMode) "创建 Yfuse 账号" else "登录 Yfuse 账号", style = sc(15f, 700), color = palette.text)
        Spacer(Modifier.height(5.dp))
        Text("账号服务：IP 直连 · HTTPS", style = mr(10.5f, 400), color = palette.sub2)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.take(40) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("账号名") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            enabled = !busy,
        )
        Spacer(Modifier.height(9.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(128) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码（至少 $MIN_PASSWORD_LENGTH 位）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy,
        )
        if (registerMode) {
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("昵称（可选）") },
                singleLine = true,
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
        Button(
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
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (registerMode) "注册账号" else "登录账号")
        }
        TextButton(
            onClick = {
                registerMode = !registerMode
                password = ""
                error = null
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy,
        ) {
            Text(if (registerMode) "已有账号？去登录" else "没有账号？创建一个")
        }
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

    LaunchedEffect(user.updatedAtEpochMs) {
        nickname = user.nickname
        avatarId = user.avatarId
    }

    AccountCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(user.nickname, user.avatarId)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(user.nickname, style = sc(15f, 700), color = palette.text)
                Text("@${user.username}", style = mr(10.5f, 400), color = palette.sub2)
            }
            Text("已登录", style = mr(10.5f, 600), color = Brand.Online)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(24) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("昵称") },
            singleLine = true,
            enabled = !busy && !state.syncing,
        )
        Spacer(Modifier.height(10.dp))
        AvatarPicker(
            selected = avatarId,
            enabled = !busy && !state.syncing,
            onSelect = { avatarId = it },
        )
        Spacer(Modifier.height(12.dp))
        Button(
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
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !state.syncing && nickname.isNotBlank(),
        ) { Text("保存资料") }
        TextButton(
            onClick = {
                showPasswordForm = !showPasswordForm
                currentPassword = ""
                newPassword = ""
                confirmPassword = ""
                localError = null
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy && !state.syncing,
        ) { Text(if (showPasswordForm) "取消修改密码" else "修改登录密码") }
        if (showPasswordForm) {
            OutlinedTextField(
                value = currentPassword,
                onValueChange = { currentPassword = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("当前密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新密码（至少 $MIN_PASSWORD_LENGTH 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it.take(128) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("确认新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (newPassword != confirmPassword) {
                        localError = "两次输入的新密码不一致"
                        return@Button
                    }
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
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && currentPassword.isNotEmpty() &&
                    newPassword.length >= MIN_PASSWORD_LENGTH &&
                    confirmPassword.length >= MIN_PASSWORD_LENGTH,
            ) { Text("确认修改密码") }
        }
    }

    Spacer(Modifier.height(16.dp))
    AccountCard {
        Text("加密同步", style = sc(13f, 700), color = palette.text)
        Spacer(Modifier.height(5.dp))
        Text(
            "云端版本 ${state.syncVersion}" +
                if (state.syncing) " · 正在同步…" else " · 手动同步",
            style = mr(10.5f, 400),
            color = palette.sub2,
        )
        state.message?.let {
            Spacer(Modifier.height(7.dp))
            Text(it, style = mr(10.5f, 500), color = Brand.Primary)
        }
        localError?.let {
            Spacer(Modifier.height(7.dp))
            Text(it, style = mr(10.5f, 500), color = Brand.Danger)
        }
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        // Failures already surface through the repository's status message.
                        account.uploadNow()
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !busy && !state.syncing,
            ) { Text("上传本机") }
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        account.downloadNow()
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !busy && !state.syncing && state.cloudHasData,
            ) { Text("恢复云端") }
        }
        Text(
            "不会自动上传或恢复。上传会用本机数据覆盖云端；恢复会用云端数据覆盖本机。",
            style = mr(9.5f, 400),
            color = palette.sub2,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(
            onClick = { confirmClearRemote = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !state.syncing && state.cloudHasData,
        ) {
            Text("清空服务器数据", color = Brand.Danger)
        }
    }

    Spacer(Modifier.height(16.dp))
    AccountCard {
        TextButton(
            onClick = {
                busy = true
                scope.launch {
                    account.logout()
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !state.syncing,
        ) {
            Text("退出 Yfuse 账号", color = Brand.Danger)
        }
    }

    if (confirmClearRemote) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmClearRemote = false },
            title = { Text("清空服务器数据？") },
            text = { Text("只删除这个账号的云端同步密文；账号、昵称头像和本机数据都会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        busy = true
                        localError = null
                        scope.launch {
                            val result = account.clearRemoteSync()
                            result.exceptionOrNull()?.let { localError = it.message ?: "清空失败" }
                            if (result.isSuccess) confirmClearRemote = false
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("确认清空", color = Brand.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearRemote = false }, enabled = !busy) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun AccountHeader(onBack: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .glass(RoundedCornerShape(12.dp), palette.card3, palette.border)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.ChevronLeft, "返回", tint = palette.text, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
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
            .glass(RoundedCornerShape(18.dp), palette.card, palette.border)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        content = content,
    )
}

@Composable
private fun AvatarPicker(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(WatchTogetherPreferences.AVATAR_COUNT) { id ->
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .glass(
                        CircleShape,
                        if (id == selected) Brand.Primary.copy(alpha = 0.20f) else LocalPalette.current.card2,
                        if (id == selected) Brand.Primary else LocalPalette.current.border,
                    )
                    .let { if (enabled) it.clickable { onSelect(id) } else it },
                contentAlignment = Alignment.Center,
            ) {
                Text((id + 1).toString(), style = sc(12f, 700), color = LocalPalette.current.text)
            }
        }
    }
}

@Composable
private fun AccountAvatar(nickname: String, avatarId: Int) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .glass(CircleShape, Brand.Primary.copy(alpha = 0.18f), Brand.Primary.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            nickname.take(1).ifBlank { (avatarId + 1).toString() },
            style = sc(16f, 700),
            color = Brand.Primary,
            textAlign = TextAlign.Center,
        )
    }
}
