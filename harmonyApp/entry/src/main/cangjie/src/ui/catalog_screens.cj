package com.yfuse.harmony.entry

import com.yfuse.harmony.entry.design.DesignTokens

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
            Text("Yfuse").fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold)
            Text(if (restoring) { "正在恢复服务器与播放会话…" } else { "把影院带回家" }).fontColor(Color(DesignTokens.darkText)).fontSize(13)
            Blank()
        }.width(100.percent).height(100.percent).justifyContent(FlexAlign.Center)
    }
}

@Component
class TmdbInfoScreen {
    var onOpenDetail: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Stack { Column { Blank().height(180); Text("影视资料").fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold) }.padding(18) }.height(310)
            Text("TMDB 评分 · 年份 · 状态").fontColor(Color(DesignTokens.darkText)).fontSize(11)
            Text("剧情简介").fontColor(Color(DesignTokens.darkText)).fontSize(18).fontWeight(FontWeight.Bold)
            Text("资料页与服务器详情页分开：未入库内容只提供发现、收藏和资源入口。").fontColor(Color(DesignTokens.darkText)).fontSize(13)
            Row { Button("查找资源").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { onOpenDetail() }); Button("加入收藏").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("打开日历").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
            Text("演职员").fontColor(Color(DesignTokens.darkText)).fontSize(18).fontWeight(FontWeight.Bold)
            Text("相关推荐").fontColor(Color(DesignTokens.darkText)).fontSize(18).fontWeight(FontWeight.Bold)
        }.padding(18).width(100.percent) }.height(100.percent)
    }
}

@Component
class LibraryGridScreen {
    @State var filterOpen: Bool = false
    var onOpenDetail: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Row { Button("‹").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Text("全部媒体").fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold); Blank(); Button("筛选").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { filterOpen = !filterOpen }) }
            if (filterOpen) {
                Column {
                    Row { Button("电影").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("剧集").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("未播放").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("收藏").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
                    Row { Button("名称").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("上映时间").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("添加时间").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall); Button("正序").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall) }
                }.padding(12)
            }
            Row { PosterCard("影片"); PosterCard("影片"); PosterCard("影片") }
            Row { PosterCard("影片"); PosterCard("影片"); PosterCard("影片") }
            Button("打开所选媒体").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { onOpenDetail() })
        }.padding(18).width(100.percent) }.height(100.percent)
    }
}

@Component
class ServerEditorScreen {
    @State var protocol: String = "https"
    @State var provider: String = "emby"
    func build() {
        Scroll { Column {
            Text("添加服务器").fontColor(Color(DesignTokens.darkText)).fontSize(26).fontWeight(FontWeight.Bold)
            Row { Button("Emby").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { provider = "emby" }); Button("Jellyfin").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { provider = "jellyfin" }); Button("Plex").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { provider = "plex" }) }
            Row { Button("HTTPS").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { protocol = "https" }); Button("HTTP").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).onClick(() => { protocol = "http" }) }
            TextInput(placeholder: "服务器地址").backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkText)).placeholderColor(Color(DesignTokens.darkSecondary))
            TextInput(placeholder: "端口").backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkText)).placeholderColor(Color(DesignTokens.darkSecondary))
            TextInput(placeholder: "基础路径（可选）").backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkText)).placeholderColor(Color(DesignTokens.darkSecondary))
            if (provider == "plex") {
                Text("Plex 将打开 PIN 授权流程").fontColor(Color(DesignTokens.darkText)).fontSize(13)
                Button("获取 Plex PIN").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).width(100.percent)
            } else {
                TextInput(placeholder: "用户名").backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkText)).placeholderColor(Color(DesignTokens.darkSecondary))
                TextInput(placeholder: "密码").backgroundColor(Color(DesignTokens.darkSurface)).fontColor(Color(DesignTokens.darkText)).placeholderColor(Color(DesignTokens.darkSecondary))
                Button("Quick Connect / PIN").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).width(100.percent)
            }
            Button("测试并保存").backgroundColor(Color(DesignTokens.darkAccent)).fontColor(Color(DesignTokens.darkOnAccent)).borderRadius(DesignTokens.radiusSmall).width(100.percent)
        }.padding(18).width(100.percent) }.height(100.percent)
    }
}
