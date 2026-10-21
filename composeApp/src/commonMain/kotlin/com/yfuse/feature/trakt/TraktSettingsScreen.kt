package com.yfuse.feature.trakt

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.SwitchRow
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.contentHandoff
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.trakt.TraktOperation
import com.yfuse.core.trakt.TraktRepository
import com.yfuse.feature.profile.SettingsPage
import com.yfuse.feature.profile.rememberComposedPageStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TraktSettingsScreen(
    store: Store<TraktSettingsIntent, TraktSettingsState, Nothing>,
    onBack: () -> Unit,
) {
    val settings by store.states.collectAsState(store.state)
    val state = settings.trakt
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var copiedCode by remember(state.challenge) { mutableStateOf(false) }
    DisposableEffect(store) {
        store.accept(TraktSettingsIntent.PageShown)
        onDispose { store.accept(TraktSettingsIntent.PageHidden) }
    }
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
                            "打开授权",
                            embedded = true,
                            loading = state.busy && state.operation == TraktOperation.Authorize,
                            onClick =
                                if (settings.canConnect) {
                                    (
                                        {
                                            store.accept(TraktSettingsIntent.Connect)
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
                        SettingRow("断开 Trakt", "解除当前资料的连接", embedded = true, onClick = {
                            store.accept(TraktSettingsIntent.Disconnect)
                        })
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
                        challenge.userCode?.let { code ->
                            SettingsDivider()
                            SettingRow("复制授权码", if (copiedCode) "已复制" else code, embedded = true, onClick = {
                                clipboard.setText(AnnotatedString(code))
                                copiedCode = true
                            })
                        }
                        settings.verificationPage?.let { page ->
                            SettingsDivider()
                            SettingRow(
                                "打开授权网页",
                                "前往 Trakt",
                                embedded = true,
                                onClick = { uriHandler.openUri(page) },
                            )
                        }
                        SettingsDivider()
                        SettingRow("取消授权", "结束本次授权", embedded = true, onClick = {
                            store.accept(TraktSettingsIntent.CancelAuthorization)
                        })
                    }
                }
            }
        }
        if (state.connected) {
            item {
                Section(title = "导入与同步") {
                    SettingsCard {
                        SettingRow(
                            "一键同步全部",
                            if (state.operation == TraktOperation.FullSync) {
                                state.message.orEmpty()
                            } else {
                                "播放记录 · 收藏 · 待看列表"
                            },
                            embedded = true,
                            loading = state.operation == TraktOperation.FullSync,
                            onClick = if (state.busy) null else ({ store.accept(TraktSettingsIntent.SyncLibrary) }),
                        )
                        if (state.operation == TraktOperation.FullSync) {
                            SettingsDivider()
                            SettingRow("停止同步", "已完成内容会保留", embedded = true, onClick = {
                                store.accept(TraktSettingsIntent.CancelLibrarySync)
                            })
                        }
                        state.lastLibrarySyncAt?.let { time ->
                            SettingsDivider()
                            SettingRow(
                                "上次完成同步",
                                remember(
                                    time,
                                ) { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time)) },
                                embedded = true,
                            )
                        }
                        SettingsDivider()
                        SettingRow(
                            "导入想看单",
                            if (state.busy && state.operation == TraktOperation.Watchlist) "正在导入想看单…" else "合并到当前资料",
                            embedded = true,
                            loading = state.busy && state.operation == TraktOperation.Watchlist,
                            onClick = if (state.busy) null else ({ store.accept(TraktSettingsIntent.ImportWatchlist) }),
                        )
                        SettingsDivider()
                        SettingRow(
                            "导入观看历史",
                            if (state.busy && state.operation == TraktOperation.History) "正在导入观看历史…" else "合并到当前资料",
                            embedded = true,
                            loading = state.busy && state.operation == TraktOperation.History,
                            onClick = if (state.busy) null else ({ store.accept(TraktSettingsIntent.ImportHistory) }),
                        )
                        SettingsDivider()
                        SwitchRow("播放上报", state.scrobbling, true, onChange = {
                            store.accept(TraktSettingsIntent.SetScrobbling(it))
                        })
                        if (state.pending > 0) {
                            SettingsDivider()
                            SettingRow(
                                "重试待上报内容",
                                state.pending.toString() + " 项",
                                embedded = true,
                                onClick =
                                    if (state.busy) {
                                        null
                                    } else {
                                        ({ store.accept(TraktSettingsIntent.RetryPending) })
                                    },
                            )
                        }
                    }
                }
            }
        }
        item {
            Section(title = "同步范围") {
                ThemeText(
                    "一键同步会双向合并当前家庭资料的收藏与待看列表；已同步条目的新增、取消也会同步。初次合并保留本机删除决定，冲突时优先保留本次本机修改。" +
                        "观看历史仅合并有影片标识的已看电影与单集，不上传未看完的记录，不向 Trakt 删除历史，也不覆盖本机或媒体服务器的续播进度。" +
                        "播放上报可单独开启。仅有服务器内部编号、无法匹配或 Trakt 不支持的条目会跳过。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
        }
        state.syncResults.forEach { result ->
            item {
                ThemeText(
                    result.summary,
                    color = if (result.error == null) palette.sub2 else palette.error,
                    modifier = Modifier.padding(horizontal = Dimens.pageHorizontal).contentHandoff(result).liveStatus(),
                )
            }
        }
        listOfNotNull(state.message, state.error).forEach { notice ->
            item {
                ThemeText(
                    notice,
                    color = if (notice == state.error) palette.error else palette.success,
                    modifier = Modifier.padding(horizontal = Dimens.pageHorizontal).contentHandoff(notice).liveStatus(),
                )
            }
        }
    }
}

/**
 * The same page for the television's settings, which swap their pages in place rather than through
 * the phone's page stack; the store lives as long as the page is composed there.
 */
@Composable
fun TraktSettingsScreen(
    repository: TraktRepository,
    onBack: () -> Unit,
    television: Boolean = false,
) {
    val store =
        rememberComposedPageStore(repository, television) {
            // The app's StoreFactory is this one; the television reaches the page without it.
            TraktSettingsStoreFactory(DefaultStoreFactory(), repository, television).create()
        }
    TraktSettingsScreen(store, onBack)
}
