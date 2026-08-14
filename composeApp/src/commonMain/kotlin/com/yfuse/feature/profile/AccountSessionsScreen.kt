package com.yfuse.feature.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.account.AccountDeviceSession
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.formDivider
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import kotlinx.coroutines.launch

@Composable
internal fun AccountSessionsScreen(
    account: AccountRepository,
    onBack: () -> Unit,
) {
    val accountState by account.state.collectAsState()
    val signedIn = accountState as? AccountState.SignedIn
    val userId = signedIn?.session?.user?.id
    val scope = rememberCoroutineScope()

    var sessions by remember(userId) { mutableStateOf<List<AccountDeviceSession>>(emptyList()) }
    var loading by remember(userId) { mutableStateOf(false) }
    var loadedOnce by remember(userId) { mutableStateOf(false) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    var revokingIds by remember(userId) { mutableStateOf<Set<String>>(emptySet()) }
    var bulkBusy by remember(userId) { mutableStateOf(false) }
    var confirmRevokeOthers by remember(userId) { mutableStateOf(false) }
    var confirmRevokeAll by remember(userId) { mutableStateOf(false) }

    suspend fun reloadSessions() {
        if (userId == null || loading) return
        loading = true
        error = null
        account
            .sessions()
            .onSuccess { loaded ->
                // A session id is the credential identity. Device names are deliberately not
                // used here because logging in twice on one phone creates two valid sessions.
                sessions = deduplicateAccountSessions(loaded)
                loadedOnce = true
            }.onFailure { error = it.message ?: "无法读取登录设备" }
        loading = false
    }

    LaunchedEffect(userId) {
        if (userId != null) reloadSessions()
    }

    if (accountState is AccountState.SignedIn) {
        AccountSessionsContent(
            sessions = sessions,
            loading = loading,
            loadedOnce = loadedOnce,
            error = error,
            revokingIds = revokingIds,
            bulkBusy = bulkBusy,
            onBack = onBack,
            onRefresh = { scope.launch { reloadSessions() } },
            onRevokeSession = { sessionId ->
                revokingIds = revokingIds + sessionId
                error = null
                scope.launch {
                    account
                        .revokeSession(sessionId)
                        .onSuccess { sessions = sessions.filterNot { it.id == sessionId } }
                        .onFailure { error = it.message ?: "撤销失败" }
                    revokingIds = revokingIds - sessionId
                }
            },
            onRevokeOthers = { confirmRevokeOthers = true },
            onRevokeAll = { confirmRevokeAll = true },
        )
    } else {
        AccountSessionsUnavailableContent(accountState = accountState, onBack = onBack)
    }

    if (confirmRevokeOthers) {
        ConfirmDialog(
            title = "退出其他设备？",
            message = "其他设备的访问令牌和刷新令牌会立即失效，当前设备保持登录。",
            confirmLabel = "确认退出",
            destructive = true,
            onConfirm = {
                confirmRevokeOthers = false
                bulkBusy = true
                error = null
                scope.launch {
                    account
                        .revokeOtherSessions()
                        .onSuccess { reloadSessions() }
                        .onFailure { error = it.message ?: "无法退出其他设备" }
                    bulkBusy = false
                }
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
                bulkBusy = true
                error = null
                scope.launch {
                    account
                        .revokeAllSessions()
                        .onSuccess { onBack() }
                        .onFailure { error = it.message ?: "无法退出全部设备" }
                    bulkBusy = false
                }
            },
            onDismiss = { confirmRevokeAll = false },
        )
    }
}

/**
 * Repository-free rendering seam for previews, debug showcases and deterministic UI tests.
 * Callers provide credential-shaped rows and callbacks; no authenticated account is required.
 */
@Composable
internal fun AccountSessionsContent(
    sessions: List<AccountDeviceSession>,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onRevokeSession: (String) -> Unit = {},
    onRevokeOthers: () -> Unit = {},
    onRevokeAll: () -> Unit = {},
    loading: Boolean = false,
    loadedOnce: Boolean = true,
    error: String? = null,
    revokingIds: Set<String> = emptySet(),
    bulkBusy: Boolean = false,
) {
    val palette = LocalPalette.current
    val bottomContentInset = systemNavigationContentInset()
    val uniqueSessions = deduplicateAccountSessions(sessions)
    val currentSession = uniqueSessions.firstOrNull(AccountDeviceSession::current)
    val otherSessions = uniqueSessions.filterNot { it.id == currentSession?.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding =
            PaddingValues(
                top = SettingsHeaderTop,
                bottom = bottomContentInset,
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SessionsHeader(
                count = uniqueSessions.size,
                loading = loading,
                refreshEnabled = !loading && !bulkBusy,
                onBack = onBack,
                onRefresh = onRefresh,
            )
        }

        if (loading && !loadedOnce) {
            item {
                SessionSurface { LoadingLine("正在读取登录设备…") }
            }
        } else {
            error?.let { message ->
                item {
                    SessionSurface {
                        Text("加载失败", style = AppTypography.body.strong, color = palette.error)
                        Spacer(Modifier.height(5.dp))
                        Text(message, style = AppTypography.caption.regular, color = palette.sub2)
                        Spacer(Modifier.height(12.dp))
                        YfButton(
                            label = "重新加载",
                            onClick = onRefresh,
                            tone = YfButtonTone.Secondary,
                        )
                    }
                }
            }

            item { SessionSectionLabel("当前设备", currentSession?.let { "保持登录" }) }
            item {
                if (currentSession == null) {
                    SessionSurface {
                        Text("未找到当前会话", style = AppTypography.body.strong, color = palette.text)
                        Spacer(Modifier.height(4.dp))
                        Text("请刷新后重试。", style = AppTypography.caption.regular, color = palette.sub2)
                    }
                } else {
                    SessionSurface {
                        SessionDeviceRow(session = currentSession, current = true)
                    }
                }
            }

            item { SessionSectionLabel("其他设备", "${otherSessions.size} 个") }
            if (otherSessions.isEmpty()) {
                item {
                    SessionSurface {
                        Text("没有其他登录设备", style = AppTypography.body.medium, color = palette.text)
                        Spacer(Modifier.height(4.dp))
                        Text("当前只有这台设备保持登录。", style = AppTypography.caption.regular, color = palette.sub2)
                    }
                }
            } else {
                item {
                    SessionSurface {
                        otherSessions.forEachIndexed { index, session ->
                            if (index > 0) SessionDivider()
                            SessionDeviceRow(
                                session = session,
                                current = false,
                                revoking = bulkBusy || session.id in revokingIds,
                                onRevoke = { onRevokeSession(session.id) },
                            )
                        }
                    }
                }
            }

            item {
                BulkSessionActions(
                    hasOtherSessions = otherSessions.isNotEmpty(),
                    busy = bulkBusy,
                    onRevokeOthers = onRevokeOthers,
                    onRevokeAll = onRevokeAll,
                )
            }
        }
    }
}

@Composable
private fun AccountSessionsUnavailableContent(
    accountState: AccountState,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val bottomContentInset = systemNavigationContentInset()
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding =
            PaddingValues(
                top = SettingsHeaderTop,
                bottom = bottomContentInset,
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SessionsHeader(
                count = 0,
                loading = accountState == AccountState.Restoring,
                refreshEnabled = false,
                onBack = onBack,
                onRefresh = {},
            )
        }
        item {
            SessionSurface {
                when (accountState) {
                    AccountState.Restoring -> LoadingLine("正在恢复账号…")
                    is AccountState.RestoreFailed -> {
                        Text("暂时无法读取会话", style = AppTypography.body.strong, color = palette.text)
                        Spacer(Modifier.height(5.dp))
                        Text(accountState.message, style = AppTypography.caption.regular, color = palette.sub2)
                    }
                    AccountState.SignedOut -> {
                        Text("当前账号已退出", style = AppTypography.body.strong, color = palette.text)
                        Spacer(Modifier.height(5.dp))
                        Text("返回账号页可重新登录。", style = AppTypography.caption.regular, color = palette.sub2)
                        Spacer(Modifier.height(12.dp))
                        YfButton("返回账号页", onBack)
                    }
                    is AccountState.SignedIn -> Unit
                }
            }
        }
    }
}

@Composable
private fun SessionsHeader(
    count: Int,
    loading: Boolean,
    refreshEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().offset(x = SettingsBackInset - Dimens.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsBackButton(onBack)
        Column(
            Modifier
                .padding(start = 10.dp)
                .weight(1f),
        ) {
            Text("登录与会话", style = AppTypography.section.strong, color = palette.text)
            Text(
                if (loading) "正在刷新…" else "$count 个会话保持登录",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
        }
        Box(
            modifier =
                Modifier
                    .pressable(
                        enabled = refreshEnabled,
                        onClickLabel = "刷新登录设备",
                        onClick = onRefresh,
                    ).touchTarget()
                    .liquidGlass(
                        shape = AppShapes.control,
                        fill = palette.card2,
                        border = null,
                        over = palette.background,
                    ).size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = palette.sub2,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionSectionLabel(
    title: String,
    value: String?,
) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppTypography.caption.strong, color = palette.sub2, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = AppTypography.caption.medium, color = palette.sub2) }
    }
}

@Composable
private fun SessionDeviceRow(
    session: AccountDeviceSession,
    current: Boolean,
    revoking: Boolean = false,
    onRevoke: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .liquidGlass(
                    shape = AppShapes.control,
                    fill = if (current) Brand.Online.copy(alpha = 0.14f) else palette.card2,
                    border = null,
                    over = palette.card,
                    sheen = 0.38f,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = if (current) Brand.Online else palette.text,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.deviceName.ifBlank { "未命名设备" },
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "最近活动 ${formatSessionActivity(session.lastSeenAtEpochMs)}",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Text("此设备", style = AppTypography.caption.strong, color = Brand.Online)
        } else if (onRevoke != null) {
            CompactSessionAction(
                label = "撤销",
                accessibilityLabel = "撤销${session.deviceName.ifBlank { "未命名设备" }}的会话",
                enabled = !revoking,
                loading = revoking,
                destructive = true,
                onClick = onRevoke,
            )
        }
    }
}

@Composable
private fun CompactSessionAction(
    label: String,
    accessibilityLabel: String = label,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        modifier =
            Modifier
                .heightIn(min = 42.dp)
                .pressable(
                    enabled = enabled && !loading,
                    haptic = if (destructive) HapticSignal.Confirm else HapticSignal.Select,
                    onClickLabel = accessibilityLabel,
                    onClick = onClick,
                ).touchTarget()
                .liquidGlass(
                    shape = AppShapes.pill,
                    fill =
                        if (destructive) {
                            palette.error.copy(alpha = 0.10f)
                        } else {
                            palette.card2
                        },
                    border = null,
                    over = palette.card,
                    sheen = 0.35f,
                ).padding(horizontal = 13.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                color = if (destructive) palette.error else accent.accent,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                label,
                style = AppTypography.caption.strong,
                color = if (destructive) palette.error else accent.accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BulkSessionActions(
    hasOtherSessions: Boolean,
    busy: Boolean,
    onRevokeOthers: () -> Unit,
    onRevokeAll: () -> Unit,
) {
    val palette = LocalPalette.current
    SessionSurface {
        BulkSessionActionRow(
            icon = AppIcons.Lock,
            title = "退出其他设备",
            detail = "当前设备保持登录",
            onClick = onRevokeOthers,
            enabled = hasOtherSessions && !busy,
        )
        SessionDivider()
        BulkSessionActionRow(
            icon = AppIcons.Close,
            title = "全部设备退出",
            detail = "包括当前设备",
            onClick = onRevokeAll,
            enabled = !busy,
            destructive = true,
        )
    }
}

@Composable
private fun BulkSessionActionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    enabled: Boolean,
    destructive: Boolean = false,
) {
    val palette = LocalPalette.current
    val contentColor = if (destructive) palette.error else palette.text
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .pressable(
                    enabled = enabled,
                    haptic = if (destructive) HapticSignal.Confirm else HapticSignal.Select,
                    onClickLabel = title,
                    onClick = onClick,
                ).touchTarget()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.body.strong, color = contentColor)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = AppTypography.caption.regular, color = palette.sub2)
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = palette.sub2,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SessionDivider() {
    Spacer(Modifier.height(11.dp))
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(Dimens.hairline)
            .background(formDivider()),
    )
    Spacer(Modifier.height(11.dp))
}

@Composable
private fun LoadingLine(label: String) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = palette.sub2)
        Spacer(Modifier.width(9.dp))
        Text(label, style = AppTypography.body.medium, color = palette.sub2)
    }
}

@Composable
private fun SessionSurface(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .liquidGlass(
                    shape = AppShapes.card,
                    fill = palette.card,
                    border = null,
                    over = palette.background,
                    sheen = 0.46f,
                ).padding(horizontal = 16.dp, vertical = 15.dp),
        content = content,
    )
}

internal fun deduplicateAccountSessions(sessions: List<AccountDeviceSession>): List<AccountDeviceSession> =
    sessions.distinctBy(AccountDeviceSession::id)
