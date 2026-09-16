package com.yfuse.feature.trakt

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.trakt.TraktRepository
import com.yfuse.feature.profile.Section
import com.yfuse.feature.profile.SettingRow
import com.yfuse.feature.profile.SettingsCard
import com.yfuse.feature.profile.SettingsDivider
import com.yfuse.feature.profile.SettingsPage
import com.yfuse.feature.profile.SwitchRow
import io.ktor.http.Url

@Composable
fun TraktSettingsScreen(
    repository: TraktRepository,
    onBack: () -> Unit,
    television: Boolean = false,
) {
    val state by repository.state.collectAsState()
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.signedIn) { repository.refreshConfiguration() }
    DisposableEffect(repository) { onDispose { repository.cancelAuthorization() } }
    val canConnect =
        state.signedIn &&
            !state.busy &&
            (
                if (television) {
                    state.configuration?.deviceAvailable == true
                } else {
                    state.configuration?.oauthAvailable ==
                        true
                }
            )
    SettingsPage(title = "Trakt", onBack = onBack) {
        item {
            Section(title = "账号连接") {
                SettingsCard {
                    SettingRow(
                        "连接状态",
                        when {
                            !state.signedIn -> "请先登录鱼服账号"
                            state.connected -> "已连接当前家庭资料"
                            state.busy -> "正在处理…"
                            else -> "未连接"
                        },
                        embedded = true,
                    )
                    if (!state.connected && state.challenge == null) {
                        SettingsDivider()
                        SettingRow(
                            "连接 Trakt",
                            "打开授权 ›",
                            embedded = true,
                            onClick =
                                if (canConnect) {
                                    (
                                        {
                                            repository.connect(television)
                                        }
                                    )
                                } else {
                                    null
                                },
                        )
                    }
                    if (state.configuration?.let { !it.deviceAvailable && !it.oauthAvailable } == true) {
                        SettingsDivider()
                        SettingRow("接入状态", "服务尚未配置，暂时无法授权", embedded = true)
                    }
                    if (state.connected) {
                        SettingsDivider()
                        SettingRow("断开 Trakt", "解除当前资料的连接", embedded = true, onClick = repository::disconnect)
                    }
                }
            }
        }
        state.challenge?.let { challenge ->
            item {
                Section(title = "完成授权") {
                    SettingsCard {
                        SettingRow(
                            "授权",
                            challenge.userCode?.let { "授权码：" + it + " · " + challenge.verificationUrl }
                                ?: "在 Trakt 网页确认授权后返回",
                            embedded = true,
                        )
                        val url = runCatching { Url(challenge.verificationUrl) }.getOrNull()
                        if (url != null &&
                            url.protocol.name == "https" &&
                            url.host in setOf("auth.trakt.tv", "trakt.tv", "app.trakt.tv")
                        ) {
                            SettingsDivider()
                            SettingRow(
                                "打开授权网页",
                                "前往 Trakt ›",
                                embedded = true,
                                onClick = { uriHandler.openUri(challenge.verificationUrl) },
                            )
                        }
                        SettingsDivider()
                        SettingRow("取消授权", "结束本次授权", embedded = true, onClick = repository::cancelAuthorization)
                    }
                }
            }
        }
        if (state.connected) {
            item {
                Section(title = "导入与同步") {
                    SettingsCard {
                        SettingRow(
                            "导入想看单",
                            if (state.busy) "正在处理…" else "合并到当前资料 ›",
                            embedded = true,
                            onClick = if (state.busy) null else repository::importWatchlist,
                        )
                        SettingsDivider()
                        SettingRow(
                            "导入观看历史",
                            if (state.busy) "正在处理…" else "合并到当前资料 ›",
                            embedded = true,
                            onClick = if (state.busy) null else repository::importHistory,
                        )
                        SettingsDivider()
                        SwitchRow("播放上报", state.scrobbling, true, onChange = repository::setScrobbling)
                        if (state.pending > 0) {
                            SettingsDivider()
                            SettingRow(
                                "重试待上报内容",
                                state.pending.toString() + " 项",
                                embedded = true,
                                onClick = if (state.busy) null else repository::retryPending,
                            )
                        }
                    }
                }
            }
        }
        item {
            Section(title = "同步范围") {
                ThemeText(
                    "观看历史与想看单导入当前家庭资料，并保留已有选择。播放上报可单独开启，不会覆盖媒体服务器的观看进度。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
        }
        listOfNotNull(state.message, state.error).forEach { notice ->
            item {
                ThemeText(
                    notice,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
    }
}
