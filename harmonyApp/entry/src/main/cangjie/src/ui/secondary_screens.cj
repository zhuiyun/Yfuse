package com.yfuse.harmony.entry

import com.yfuse.harmony.entry.design.DesignTokens
import com.yfuse.harmony.entry.data.*
import std.collection.*
import ohos.base.*
import ohos.arkui.component.*
import ohos.arkui.state_management.*
import ohos.arkui.state_macro_manage.*

/** Every displayed title, duration and source comes from the selected provider result. */
@Component
class DetailScreen {
    var detail: Option<MediaDetailModel> = None
    var sources: ArrayList<MediaSourceModel> = ArrayList<MediaSourceModel>()
    var loading: Bool = false
    var onPlay: () -> Unit = { => }
    var onPlaySource: (String) -> Unit = { _ => }
    var onOpenEpisodes: () -> Unit = { => }
    @State var sourceOpen: Bool = false

    func build() {
        Column {
            if (let Some(value) <- detail) {
                Text(value.item.title).fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold)
                Text(value.item.subtitle).fontColor(Color(DesignTokens.darkSecondary))
                Text(if (value.item.overview.isEmpty()) { "服务器未提供简介" } else { value.item.overview }).fontColor(Color(DesignTokens.darkText))
                if (value.resumePositionMs > 0) {
                    Text("服务器续播记录：${value.resumePositionMs / 60000} 分钟").fontColor(Color(DesignTokens.darkSecondary))
                }
                Row {
                    Button("播放 / 继续观看").enabled(!loading).onClick({ => onPlay() })
                    Button("片源 (${sources.size})").enabled(!loading).onClick({ => sourceOpen = !sourceOpen })
                }
                if (sourceOpen) {
                    if (sources.isEmpty()) { Text("服务器未返回可播放片源").fontColor(Color(DesignTokens.darkSecondary)) }
                    ForEach(sources.toArray(), { source: MediaSourceModel =>
                        Button(source.name + " · " + source.container + " · ${source.width}×${source.height}")
                            .enabled(!loading).onClick({ => onPlaySource(source.id) }).width(100.percent)
                    })
                    Text("重新请求所选片源后播放；需要额外请求头或跨域认证的片源将显示错误。").fontColor(Color(DesignTokens.darkSecondary))
                }
                Button("收藏 / 剧集列表 · 暂不可用").enabled(false)
                Text("尚未接入收藏写入和按季枚举，请返回媒体库选择具体单集。").fontColor(Color(DesignTokens.darkSecondary))
            } else {
                Text(if (loading) { "正在加载媒体详情…" } else { "尚未选择媒体" }).fontColor(Color(DesignTokens.darkText))
                Text("从已连接服务器的媒体库或搜索结果打开真实条目。").fontColor(Color(DesignTokens.darkSecondary))
            }
        }.padding(18).width(100.percent)
    }
}

@Component
class SeasonEpisodesScreen {
    var onPlay: () -> Unit = { => }
    func build() {
        FeatureUnavailable("剧集", "尚未连接按季剧集列表。请返回媒体库或搜索选择具体单集。")
    }
}

@Component
class CalendarScreen {
    var onOpenDetail: () -> Unit = { => }
    func build() {
        FeatureUnavailable("追剧日历", "尚未配置日历数据源与 TMDB 授权，没有已验证的播出日期可显示。")
    }
}

@Component
class DownloadsScreen {
    var onBrowseLibrary: () -> Unit = { => }
    func build() {
        Column {
            FeatureUnavailable("下载管理", "当前 Harmony 版本尚未连接下载传输和文件存储，不能创建离线任务。")
            Button("浏览媒体库").onClick({ => onBrowseLibrary() })
        }.padding(18).width(100.percent)
    }
}

@Component
class AccountSettingsScreen {
    var onOpenSessions: () -> Unit = { => }
    var onOpenServers: () -> Unit = { => }
    func build() {
        Column {
            Text("账户与同步").fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold)
            Text("播放进度可保存到本机并上报当前媒体服务器；跨设备加密账户同步尚未接入。").fontColor(Color(DesignTokens.darkSecondary))
            Button("管理已连接的服务器账户").onClick({ => onOpenServers() })
            Button("云端登录 / 导出 / 删除账户 · 暂不可用").enabled(false)
            Button("设备会话说明").onClick({ => onOpenSessions() })
        }.padding(18).width(100.percent)
    }
}

@Component
class AccountSessionsScreen {
    func build() {
        FeatureUnavailable("设备会话", "尚未接入云端会话查询和撤销，无法确认当前设备或其他设备的登录状态。")
    }
}

@Builder
func FeatureUnavailable(title: String, reason: String) {
    Column {
        Text(title).fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold)
        Text(reason).fontColor(Color(DesignTokens.darkSecondary))
        Button("暂不可用").enabled(false).backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkSecondary))
    }.padding(18).width(100.percent)
}
