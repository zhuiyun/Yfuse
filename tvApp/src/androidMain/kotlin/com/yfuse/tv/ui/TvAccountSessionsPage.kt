package com.yfuse.tv.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import com.yfuse.core.account.AccountState
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.tv.focus.requestFocusWhenAttached
import kotlinx.coroutines.launch

@Composable
internal fun TvAccountSessionsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val account by component.account.state.collectAsState()
    val userId = (account as? AccountState.SignedIn)?.session?.user?.id
    key(userId) {
        val scope = rememberCoroutineScope()
        val controller =
            remember(component.account) {
                TvAccountSessionsController(
                    scope,
                    component.account::sessions,
                    component.account::revokeSession,
                    component.account::revokeOtherSessions,
                )
            }
        TvAccountSessionsContent(controller, userId != null, focusMemory, navigationRequester, firstRowRequester)
    }
}

@Composable
private fun TvAccountSessionsContent(
    controller: TvAccountSessionsController,
    signedIn: Boolean,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val state by controller.state.collectAsState()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bulkRequester = remember { FocusRequester() }
    val returnToRefresh = {
        scope.launch {
            list.scrollToItem(0)
            firstRowRequester.requestFocusWhenAttached()
        }
        Unit
    }
    LaunchedEffect(controller, signedIn) { if (signedIn) controller.refresh() }
    TvSettingsPageScaffold(page = TvSettingsPage.AccountSessions, status = state.status, state = list) {
        item(key = "sessions-refresh") {
            TvSettingRow(
                title =
                    when {
                        !signedIn -> "请先登录账号"
                        state.loading -> "正在读取…"
                        state.loadFailed -> "重新读取设备"
                        else -> "刷新设备列表"
                    },
                value = "",
                stableId = "session:refresh",
                focusMemory = focusMemory,
                onClick = controller::refresh,
                icon = AppIcons.Refresh,
                focusScope = "settings:sessions",
                enabled = signedIn && !state.busy,
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }
        if (state.loaded && !state.loading && !state.loadFailed && state.sessions.isEmpty()) {
            item(key = "sessions-empty") { TvSettingsNote("没有已登录的设备。") }
        }
        sessionRows(state, controller, focusMemory, navigationRequester, bulkRequester, returnToRefresh)
        item(key = "sessions-note") {
            TvSettingsNote("撤销后，该设备下次访问云端时会被要求重新登录。")
        }
    }
    if (state.confirmOthers) {
        TvConfirmDialog(
            title = "撤销其他所有设备？",
            message = "其他设备将需要重新登录，本机保持登录。",
            confirmLabel = "撤销其他设备",
            focusScope = "sessions-confirm",
            focusMemory = focusMemory,
            onConfirm = { if (controller.confirmRevokeOthers()) returnToRefresh() },
            onDismiss = {
                controller.dismissConfirmation()
                scope.launch { bulkRequester.requestFocusWhenAttached() }
            },
        )
    }
}

private fun LazyListScope.sessionRows(
    state: TvAccountSessionsState,
    controller: TvAccountSessionsController,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    bulkRequester: FocusRequester,
    returnToRefresh: () -> Unit,
) {
    state.sessions.forEach { session ->
        item(key = "session:${session.id}") {
            TvSettingRow(
                title = session.deviceName.ifBlank { "未命名设备" },
                value = if (session.current) "本机" else "撤销",
                stableId = "session:${session.id}",
                focusMemory = focusMemory,
                onClick = { if (controller.revoke(session.id)) returnToRefresh() },
                icon = if (session.current) AppIcons.Check else AppIcons.Close,
                focusScope = "settings:sessions",
                subtitle = "最近活动 ${formatEpoch(session.lastSeenAtEpochMs)}",
                enabled = !session.current && !state.busy,
                selected = session.current,
                navigationRequester = navigationRequester,
            )
        }
    }
    if (state.sessions.any { !it.current }) {
        item(key = "sessions-revoke-others") {
            TvSettingRow(
                title = "撤销其他所有设备",
                value = "",
                stableId = "session:revoke-others",
                focusMemory = focusMemory,
                onClick = controller::requestRevokeOthers,
                icon = AppIcons.Lock,
                focusScope = "settings:sessions",
                subtitle = "本机保持登录",
                enabled = !state.busy,
                focusRequester = bulkRequester,
                navigationRequester = navigationRequester,
            )
        }
    }
}
