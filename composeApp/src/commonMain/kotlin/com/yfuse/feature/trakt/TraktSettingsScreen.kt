package com.yfuse.feature.trakt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.trakt.TraktRepository
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeText("Trakt", style = AppTypography.section.strong, color = palette.text)
        ThemeText(
            "观看历史与想看单只导入鱼服；保留已有本地选择。播放上报可单独开启，不从 Trakt 覆盖媒体服务器的观看进度。",
            style = AppTypography.body.regular,
            color = palette.body,
        )
        if (!state.signedIn) ThemeText("请先登录鱼服账号，再连接当前个人资料的 Trakt。", color = palette.body)
        if (state.configuration?.let { !it.deviceAvailable && !it.oauthAvailable } == true) {
            ThemeText("应用维护者尚未配置 Trakt 接入。配置完成后可在这里授权，无需向鱼服提供 Trakt 密码。", color = palette.body)
        }
        if (!state.connected && state.challenge == null) {
            YfButton(
                "连接 Trakt",
                { repository.connect(television) },
                enabled =
                    state.signedIn &&
                        !state.busy &&
                        (
                            if (television) {
                                state.configuration?.deviceAvailable == true
                            } else {
                                state.configuration?.oauthAvailable ==
                                    true
                            }
                        ),
            )
        }
        state.challenge?.let { challenge ->
            challenge.userCode?.let { ThemeText("授权码：$it", style = AppTypography.section.strong, color = palette.text) }
            ThemeText(
                if (television) "请在手机浏览器访问 ${challenge.verificationUrl} 并输入授权码。" else "请在 Trakt 网页确认授权，然后返回此页。",
                color = palette.body,
            )
            val url = runCatching { Url(challenge.verificationUrl) }.getOrNull()
            if (url != null &&
                url.protocol.name == "https" &&
                url.host in setOf("auth.trakt.tv", "trakt.tv", "app.trakt.tv")
            ) {
                YfButton("打开 Trakt 授权网页", { uriHandler.openUri(challenge.verificationUrl) })
            }
            YfButton("取消授权", repository::cancelAuthorization, tone = YfButtonTone.Secondary)
        }
        if (state.connected) {
            ThemeText("已连接 · 当前个人资料", color = palette.text)
            YfButton("导入想看单", repository::importWatchlist, enabled = !state.busy)
            YfButton("导入观看历史", repository::importHistory, enabled = !state.busy)
            YfButton(if (state.scrobbling) "关闭播放上报" else "开启播放上报", {
                repository.setScrobbling(!state.scrobbling)
            }, tone = YfButtonTone.Secondary)
            ThemeText("播放上报：${if (state.scrobbling) "已开启" else "已关闭"} · 待重试 ${state.pending} 项", color = palette.body)
            if (state.pending >
                0
            ) {
                YfButton("重试待上报内容", repository::retryPending, enabled = !state.busy, tone = YfButtonTone.Secondary)
            }
            YfButton("断开 Trakt", repository::disconnect, tone = YfButtonTone.Secondary)
        }
        state.message?.let { ThemeText(it, color = palette.body) }
        state.error?.let { ThemeText(it, color = palette.body) }
        YfButton("返回", onBack, tone = YfButtonTone.Secondary)
    }
}
