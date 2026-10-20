package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import com.yfuse.core.designsystem.OperationFeedback
import com.yfuse.core.designsystem.OperationTone
import com.yfuse.feature.servers.PlexAccountUiState

@Composable
internal fun PlexAccountStage(state: PlexAccountUiState) {
    val stage =
        when (state) {
            PlexAccountUiState.Starting, is PlexAccountUiState.AwaitingAuthorization -> "1 / 3 · 授权 Plex 账号"
            PlexAccountUiState.LoadingAccount, is PlexAccountUiState.SelectingHomeUser -> "2 / 3 · 选择家庭用户"
            PlexAccountUiState.LoadingServers,
            is PlexAccountUiState.SelectingServer,
            PlexAccountUiState.Connecting,
            -> "3 / 3 · 选择并验证服务器"
            else -> return
        }
    OperationFeedback(stage, OperationTone.Info)
}
