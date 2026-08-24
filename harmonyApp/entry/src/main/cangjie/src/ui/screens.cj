package com.yfuse.harmony.entry

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*

@Component
class HomeScreen {
    var onOpenDetail: () -> Unit = { => }
    var onPlay: () -> Unit = { => }
    var onOpenTmdb: () -> Unit = { => }
    var onOpenCalendar: () -> Unit = { => }

    func build() {
        Scroll {
            Column {
                Stack {
                    Column {
                        Text("Yfuse").fontSize(26).fontWeight(FontWeight.Bold)
                        Text("把影院带回家").fontSize(13).opacity(0.72)
                        Blank().height(86)
                        Text("正在热映 · Living Poster").fontSize(11)
                        Text("连接服务器后呈现动态海报与真实取色").fontSize(18).fontWeight(FontWeight.Bold)
                        Row {
                            Button("播放").onClick(() => { onPlay() })
                            Button("详情").onClick(() => { onOpenDetail() })
                        }.height(48)
                    }.padding(20).width(100.percent)
                }.height(310)
                MediaRail("继续观看")
                MediaRail("最近添加")
                MediaRail("热门 TMDB")
                MediaRail("即将上映")
                Row {
                    Button("影视资料").onClick(() => { onOpenTmdb() })
                    Button("追剧日历").onClick(() => { onOpenCalendar() })
                }.padding(left: 18, right: 18).width(100.percent)
                Blank().height(122)
            }.width(100.percent)
        }.layoutWeight(1).width(100.percent)
    }

    @Builder
    func MediaRail(title: String) {
        Column {
            Row { Text(title).fontSize(18).fontWeight(FontWeight.Bold); Blank(); Text("更多") }.width(100.percent)
            Row {
                PosterCard("媒体内容")
                PosterCard("媒体内容")
                PosterCard("媒体内容")
            }.width(100.percent)
        }.padding(18).width(100.percent)
    }
}

@Component
class LibraryScreen {
    @State var grid: Bool = true
    var onOpenGrid: () -> Unit = { => }
    var onOpenDetail: () -> Unit = { => }
    var onPlay: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Row { Text("媒体库").fontSize(26).fontWeight(FontWeight.Bold); Blank(); Button(if (grid) { "列表" } else { "海报" }).onClick(() => { grid = !grid }) }
            Row { Button("全部"); Button("电影"); Button("剧集"); Button("筛选"); Button("排序") }.width(100.percent)
            MediaRail("继续观看")
            MediaRail("播放列表")
            MediaRail("全部媒体")
            Row {
                Button("查看全部").onClick(() => { onOpenGrid() })
                Button("打开详情").onClick(() => { onOpenDetail() })
                Button("继续播放").onClick(() => { onPlay() })
            }.width(100.percent)
            Blank().height(122)
        }.padding(18).width(100.percent) }.layoutWeight(1)
    }
    @Builder func MediaRail(title: String) {
        Column { Text(title).fontSize(18).fontWeight(FontWeight.Bold); Row { PosterCard("媒体"); PosterCard("媒体"); PosterCard("媒体") } }.width(100.percent)
    }
}

@Component
class ServersScreen {
    var onOpenEditor: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Row {
                Text("我的服务器").fontSize(26).fontWeight(FontWeight.Bold)
                Blank()
                Button("添加").onClick(() => { onOpenEditor() })
            }
            EmptyState("连接一台媒体服务器", "支持 Emby、Jellyfin 与 Plex", "添加服务器", onOpenEditor)
            Blank().height(122)
        }.padding(18).width(100.percent) }.layoutWeight(1)
    }
}

@Component
class SearchScreen {
    @State var query: String = ""
    var onOpenDetail: () -> Unit = { => }

    func build() {
        Scroll { Column {
                Text("搜索").fontSize(26).fontWeight(FontWeight.Bold)
            TextInput(text: query, placeholder: "搜索全部服务器").onChange({ value => query = value })
            if (query.isEmpty()) {
                Text("搜索历史").fontSize(18).fontWeight(FontWeight.Bold)
                Text("输入片名、剧集、演员或年份")
            } else {
                Text("全部服务器中的“${query}”").fontSize(18).fontWeight(FontWeight.Bold)
                Row { PosterCard(query); PosterCard(query); PosterCard(query) }
                Button("打开详情").onClick(() => { onOpenDetail() })
            }
            Blank().height(122)
        }.padding(18).width(100.percent) }.layoutWeight(1)
    }
}

@Component
class ProfileScreen {
    var onOpenDownloads: () -> Unit = { => }
    var onOpenAccount: () -> Unit = { => }
    var onOpenSessions: () -> Unit = { => }
    var onOpenDiscovery: () -> Unit = { => }

    func build() {
        Scroll { Column {
            Text("我的").fontSize(26).fontWeight(FontWeight.Bold)
            SettingsGroup("账户与同步", "登录账户", "设备会话", "导入与导出")
            SettingsGroup("离线", "下载管理", "缓存与空间", "仅 Wi-Fi 下载")
            SettingsGroup("播放", "播放器与解码", "字幕与弹幕", "片头片尾", "一起看")
            SettingsGroup("视觉与系统", "外观", "应用图标", "影视发现", "投屏与发现")
            SettingsGroup("支持", "诊断与日志", "检查更新", "关于 Yfuse")
            Text("快捷入口").fontSize(18).fontWeight(FontWeight.Bold)
            Row {
                Button("账户").onClick(() => { onOpenAccount() })
                Button("会话").onClick(() => { onOpenSessions() })
                Button("下载").onClick(() => { onOpenDownloads() })
                Button("影视发现").onClick(() => { onOpenDiscovery() })
            }.width(100.percent)
            Blank().height(122)
        }.padding(18).width(100.percent) }.layoutWeight(1)
    }
}

@Builder
func PosterCard(title: String) {
    Column { Blank().height(132); Text(title).fontSize(13).maxLines(1) }.width(92).height(176).margin(6).padding(8)
}

@Builder
func EmptyState(title: String, message: String, action: String, onAction!: () -> Unit = { => }) {
    Column {
        Blank().height(80)
        Text(title).fontSize(18).fontWeight(FontWeight.Bold)
        Text(message).fontSize(13)
        Button(action).onClick(() => { onAction() })
    }.width(100.percent)
}

@Builder
func SettingsGroup(title: String, first: String, second: String, third: String, fourth!: String = "") {
    Column {
        Text(title).fontSize(14).fontWeight(FontWeight.Bold)
        Button(first).width(100.percent)
        Button(second).width(100.percent)
        Button(third).width(100.percent)
        if (!fourth.isEmpty()) { Button(fourth).width(100.percent) }
    }.padding(12).width(100.percent)
}
