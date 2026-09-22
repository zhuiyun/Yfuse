package com.yfuse.harmony.entry

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*

@Component
class DetailScreen {
    @State var sourceOpen: Bool = false
    var onPlay: () -> Unit = { => }
    var onOpenEpisodes: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Stack { Column { Blank().height(190); Text("媒体详情").fontSize(26).fontWeight(FontWeight.Bold) }.padding(18).width(100.percent) }.height(320)
            Text("年份 · 分级 · 时长").fontSize(13).opacity(0.7)
            Text("内容简介会完整呈现，并保持 Android 的海报底部渐隐与页面动态取色。").fontSize(13)
            Row {
                Button("▶ 继续 · 00:42:10").onClick(() => { onPlay() })
                Button("资源").onClick(() => { sourceOpen = !sourceOpen })
                Button("收藏")
            }
            if (sourceOpen) {
                Column { Text("画质与片源").fontSize(18).fontWeight(FontWeight.Bold); Button("原始画质 · 直接播放"); Button("兼容画质 · 服务端转码") }.padding(12)
            }
            Text("选集").fontSize(18).fontWeight(FontWeight.Bold)
            Button("第 1 季").onClick(() => { onOpenEpisodes() }).width(100.percent)
            Text("演员与制作人员").fontSize(18).fontWeight(FontWeight.Bold)
            Text("相关推荐").fontSize(18).fontWeight(FontWeight.Bold)
            Blank().height(72)
        }.padding(18).width(100.percent) }.width(100.percent).height(100.percent)
    }
}

@Component
class SeasonEpisodesScreen {
    var onPlay: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Text("剧集").fontSize(26).fontWeight(FontWeight.Bold)
            EpisodeRow("第 1 集", "00:45:00", onPlay)
            EpisodeRow("第 2 集", "00:47:00", onPlay)
            EpisodeRow("第 3 集", "00:44:00", onPlay)
        }.padding(18).width(100.percent) }.width(100.percent).height(100.percent)
    }
}

@Component
class CalendarScreen {
    @State var selectedDay: Int64 = 0
    var onOpenDetail: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Text("追剧日历").fontSize(26).fontWeight(FontWeight.Bold)
            Row { Day("一", 0); Day("二", 1); Day("三", 2); Day("四", 3); Day("五", 4); Day("六", 5); Day("日", 6) }
            EpisodeRow("今天更新 · 第 8 集", "20:00", onOpenDetail)
            EpisodeRow("即将更新 · 第 4 集", "明天", onOpenDetail)
        }.padding(18).width(100.percent) }.width(100.percent).height(100.percent)
    }
    @Builder func Day(label: String, index: Int64) { Button(label).onClick(() => { selectedDay = index }).layoutWeight(1) }
}

@Component
class DownloadsScreen {
    func build() {
        Column {
            Text("下载管理").fontSize(26).fontWeight(FontWeight.Bold)
            Row { Button("全部"); Button("下载中"); Button("已完成") }
            EmptyState("暂无离线内容", "从详情页选择片源后即可下载", "浏览媒体库")
        }.padding(18).width(100.percent).height(100.percent)
    }
}

@Component
class AccountSettingsScreen {
    var onOpenSessions: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Text("账户与同步").fontSize(26).fontWeight(FontWeight.Bold)
            TextInput(placeholder: "账户邮箱")
            TextInput(placeholder: "密码")
            Button("登录并启用加密同步").width(100.percent)
            SettingsGroup("数据", "立即同步", "导出数据", "删除云端账户")
            Button("设备会话").onClick(() => { onOpenSessions() }).width(100.percent)
        }.padding(18).width(100.percent) }.height(100.percent)
    }
}

@Component
class AccountSessionsScreen {
    func build() {
        Column {
            Text("设备会话").fontSize(26).fontWeight(FontWeight.Bold)
            Text("当前设备 · HarmonyOS").fontSize(18).fontWeight(FontWeight.Bold)
            Text("其他登录设备可在此逐一撤销")
            Button("撤销其他设备").width(100.percent)
        }.padding(18).width(100.percent).height(100.percent)
    }
}

@Builder
func EpisodeRow(title: String, trailing: String, onOpen!: () -> Unit = { => }) {
    Row {
        Column { Text(title).fontSize(18).fontWeight(FontWeight.Bold); Text("简介与播放进度") }
        Blank()
        Text(trailing)
        Button("播放").onClick(() => { onOpen() })
    }.height(76).width(100.percent)
}
