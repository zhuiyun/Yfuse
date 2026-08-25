package com.yfuse.harmony.entry

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*

@Component
class SplashScreen {
    @State var restoring: Bool = true
    func build() {
        Column {
            Blank()
            Text("Yfuse").fontSize(26).fontWeight(FontWeight.Bold)
            Text(if (restoring) { "正在恢复服务器与播放会话…" } else { "把影院带回家" }).fontSize(13)
            Blank()
        }.width(100.percent).height(100.percent).justifyContent(FlexAlign.Center)
    }
}

@Component
class TmdbInfoScreen {
    var onOpenDetail: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Stack { Column { Blank().height(180); Text("影视资料").fontSize(26).fontWeight(FontWeight.Bold) }.padding(18) }.height(310)
            Text("TMDB 评分 · 年份 · 状态").fontSize(11)
            Text("剧情简介").fontSize(18).fontWeight(FontWeight.Bold)
            Text("资料页与服务器详情页分开：未入库内容只提供发现、收藏和资源入口。").fontSize(13)
            Row { Button("查找资源").onClick(() => { onOpenDetail() }); Button("加入收藏"); Button("打开日历") }
            Text("演职员").fontSize(18).fontWeight(FontWeight.Bold)
            Text("相关推荐").fontSize(18).fontWeight(FontWeight.Bold)
        }.padding(18).width(100.percent) }.height(100.percent)
    }
}

@Component
class LibraryGridScreen {
    @State var filterOpen: Bool = false
    var onOpenDetail: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Row { Button("‹"); Text("全部媒体").fontSize(26).fontWeight(FontWeight.Bold); Blank(); Button("筛选").onClick(() => { filterOpen = !filterOpen }) }
            if (filterOpen) {
                Column {
                    Row { Button("电影"); Button("剧集"); Button("未播放"); Button("收藏") }
                    Row { Button("名称"); Button("上映时间"); Button("添加时间"); Button("正序") }
                }.padding(12)
            }
            Row { PosterCard("影片"); PosterCard("影片"); PosterCard("影片") }
            Row { PosterCard("影片"); PosterCard("影片"); PosterCard("影片") }
            Button("打开所选媒体").onClick(() => { onOpenDetail() })
        }.padding(18).width(100.percent) }.height(100.percent)
    }
}

@Component
class ServerEditorScreen {
    @State var protocol: String = "https"
    @State var provider: String = "emby"
    func build() {
        Scroll { Column {
            Text("添加服务器").fontSize(26).fontWeight(FontWeight.Bold)
            Row { Button("Emby").onClick(() => { provider = "emby" }); Button("Jellyfin").onClick(() => { provider = "jellyfin" }); Button("Plex").onClick(() => { provider = "plex" }) }
            Row { Button("HTTPS").onClick(() => { protocol = "https" }); Button("HTTP").onClick(() => { protocol = "http" }) }
            TextInput(placeholder: "服务器地址")
            TextInput(placeholder: "端口")
            TextInput(placeholder: "基础路径（可选）")
            if (provider == "plex") {
                Text("Plex 将打开 PIN 授权流程").fontSize(13)
                Button("获取 Plex PIN").width(100.percent)
            } else {
                TextInput(placeholder: "用户名")
                TextInput(placeholder: "密码")
                Button("Quick Connect / PIN").width(100.percent)
            }
            if (protocol == "http") { Text("HTTP 仅建议用于可信局域网，继续前需确认明文传输风险").fontSize(11) }
            Button("测试并保存").width(100.percent)
        }.padding(18).width(100.percent) }.height(100.percent)
    }
}
