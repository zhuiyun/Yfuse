package com.yfuse.feature.filesource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.yfuse.core.cloud.CloudDriveCredentials
import com.yfuse.core.cloud.createCloudDriveAuthorization
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.feature.servers.ServerFormInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.yfuse.core.designsystem.ThemeText as Text

internal fun com.yfuse.core.filesource.FileSourceDraft.cloudCredentials() =
    CloudDriveCredentials(username, clientSecret, accessToken, refreshToken)

@Composable
internal fun CloudSourceFields(
    controller: FileSourcesController,
    state: FileSourceFormState,
) {
    val draft = state.draft
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var pending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var authorizationJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(draft.kind, draft.username, draft.clientSecret) {
        authorizationJob?.cancel()
        message = null
    }
    Column(Modifier.fillMaxWidth()) {
        ServerFormInput(
            divider = true,
            label =
                if (draft.kind ==
                    FileSourceKind.Baidu
                ) {
                    "App Key"
                } else {
                    "Client ID"
                },
            value = draft.username,
            placeholder = "开放平台应用标识",
            enabled =
                !state.submitting && !pending,
        ) {
            controller.editDraft { draft -> draft.copy(username = it) }
        }
        ServerFormInput(
            divider = true,
            label = "Secret",
            value = draft.clientSecret,
            placeholder = "应用密钥",
            password = true,
            enabled =
                !state.submitting && !pending,
        ) {
            controller.editDraft { draft -> draft.copy(clientSecret = it) }
        }
        ServerFormInput(
            divider = true,
            label = "Access Token",
            value = draft.accessToken,
            placeholder = "可选：已有访问令牌",
            password = true,
            enabled =
                !state.submitting && !pending,
        ) {
            controller.editDraft { draft -> draft.copy(accessToken = it) }
        }
        if (draft.kind == FileSourceKind.Baidu) {
            ServerFormInput(
                divider = true,
                label = "Refresh Token",
                value = draft.refreshToken,
                placeholder = "已有刷新令牌，或点击授权",
                password = true,
                enabled =
                    !state.submitting && !pending,
            ) {
                controller.editDraft { draft -> draft.copy(refreshToken = it) }
            }
            TextButton(enabled = !pending && !state.submitting && draft.username.isNotBlank(), onClick = {
                authorizationJob =
                    scope.launch {
                        pending = true
                        try {
                            val api = createCloudDriveAuthorization()
                            val credentials = draft.cloudCredentials()
                            val challenge = api.beginBaiduAuthorization(credentials)
                            message = "在百度页面输入授权码：${challenge.userCode}"
                            uri.openUri(challenge.verificationUrl)
                            val authorized =
                                withTimeoutOrNull(challenge.expiresInSeconds * 1000L) {
                                    while (true) {
                                        val seconds = challenge.intervalSeconds.coerceAtLeast(5)
                                        delay(seconds * 1000L)
                                        val granted =
                                            api.pollBaiduAuthorization(credentials, challenge.deviceCode) ?: continue
                                        controller.editDraft {
                                            if (it.kind == draft.kind &&
                                                it.username == draft.username &&
                                                it.clientSecret == draft.clientSecret
                                            ) {
                                                it.copy(
                                                    accessToken = granted.accessToken,
                                                    refreshToken = granted.refreshToken,
                                                )
                                            } else {
                                                it
                                            }
                                        }
                                        message = "授权完成，可以连接并添加"
                                        return@withTimeoutOrNull true
                                    }
                                }
                            if (authorized != true) message = "授权已过期，请重试"
                        } catch (
                            error: CancellationException,
                        ) {
                            throw error
                        } catch (_: Exception) {
                            message = "授权未完成，请核对应用信息后重试"
                        } finally {
                            pending = false
                        }
                    }
            }) { Text(if (pending) "等待百度授权…" else "使用百度账号授权", style = AppTypography.caption.medium) }
        }
        message?.let { Text(it, color = palette.sub2, style = AppTypography.caption.regular) }
    }
}
