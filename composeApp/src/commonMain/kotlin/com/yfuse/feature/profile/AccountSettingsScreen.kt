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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
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
                        Text("正在安全恢复账号…", style = AppTypography.body.medium, color = palette.sub)
                    }
                }
            }

            is AccountState.RestoreFailed -> item {
                AccountCard {
                    Text("暂时无法恢复账号", style = AppTypography.body.strong, color = palette.text)
                    Spacer(Modifier.height(6.dp))
                    Text(current.message, style = AppTypography.caption.regular, color = palette.sub)
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
            Text("头像", style = AppTypography.caption.strong, color = palette.sub)
            Spacer(Modifier.height(7.dp))
            AvatarPicker(avatarId, enabled = !busy, onSelect = { avatarId = it })
        }
        error?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, style = AppTypography.caption.medium, color = palette.error)
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
                color = if (nickname != user.nickname || avatarId != user.avatarId) {
                    accent.accent
                } else {
                    Brand.Online
                },
            )
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
            text = localError ?: state.message ?: syncIdleHint,
            style = AppTypography.caption.medium,
            color = when {
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
            Text("退出 Yfuse 账号", color = palette.error)
        }
    }

    if (confirmUpload) {
        ConfirmDialog(
            title = "用本机数据覆盖云端？",
            message = "云端当前的同步数据会被这台设备上的服务器、弹幕绑定和同步设置替换，" +
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
            message = "这台设备上的服务器、弹幕绑定和同步设置会被云端版本 " +
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
}

/**
 * What the sync card's status line says when it has no news.
 *
 * It is the warning that used to sit *under* the two buttons, which is where a warning about
 * what a button does goes unread. Standing it in the slot the buttons report into means it is
 * the thing you are already looking at when you reach for them, and it keeps that slot from
 * being empty — see the card for why the height has to be constant.
 */
private const val syncIdleHint =
    "不会自动上传或恢复。上传会用本机数据覆盖云端；恢复会用云端数据覆盖本机。"


@Composable
private fun AccountHeader(onBack: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .pressable(onClickLabel = "返回", onClick = onBack)
                .touchTarget()
                .size(34.dp)
                .glass(AppShapes.thumb, palette.card3, palette.border),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.ChevronLeft, "返回", tint = palette.text, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("账号与同步", style = AppTypography.section.strong, color = palette.text)
            Text("IP HTTPS · 敏感数据加密同步", style = AppTypography.caption.regular, color = palette.sub2)
        }
    }
}

@Composable
private fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
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
private fun AvatarPicker(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
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
                modifier = Modifier
                    .pressable(
                        enabled = enabled,
                        haptic = HapticSignal.Select,
                        role = Role.RadioButton,
                        onClick = { onSelect(id) },
                    )
                    .semantics {
                        this.selected = id == selected
                        contentDescription = "头像 ${id + 1}"
                    }
                    .touchTarget(),
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
private fun AccountAvatar(nickname: String, avatarId: Int) {
    val accent = LocalAccentColors.current
    val initial = nickname.take(1)
    if (initial.isBlank()) {
        WatchAvatar(avatarId = avatarId, size = 44.dp)
        return
    }
    Box(
        modifier = Modifier
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
